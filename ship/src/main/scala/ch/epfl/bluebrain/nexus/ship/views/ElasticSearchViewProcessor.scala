package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, ElasticSearchViewEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship.views.ElasticSearchViewProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus, ProjectMapper}
import io.circe.Decoder

import java.util.UUID

class ElasticSearchViewProcessor private (
    views: UUID => IO[ElasticSearchViews],
    projectMapper: ProjectMapper,
    viewPatcher: ViewPatcher,
    clock: EventClock
) extends EventProcessor[ElasticSearchViewEvent] {

  override def resourceType: EntityType = ElasticSearchViews.entityType

  override def decoder: Decoder[ElasticSearchViewEvent] = ElasticSearchViewEvent.serializer.codec

  override def evaluate(event: ElasticSearchViewEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: ElasticSearchViewEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1
    val project             = projectMapper.map(event.project)
    event match {
      case e: ElasticSearchViewCreated      =>
        e.id match {
          case id if id == defaultViewId => IO.unit // the default view is created on project creation
          case _                         =>
            val patchedSource = viewPatcher.patchElasticSearchViewSource(e.source)
            views(event.uuid).flatMap(_.create(e.id, project, patchedSource))
        }
      case e: ElasticSearchViewUpdated      =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         =>
            val patchedSource = viewPatcher.patchElasticSearchViewSource(e.source)
            views(event.uuid).flatMap(_.update(e.id, project, cRev, patchedSource))
        }
      case e: ElasticSearchViewDeprecated   =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         => views(event.uuid).flatMap(_.deprecate(e.id, project, cRev))
        }
      case e: ElasticSearchViewUndeprecated =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         => views(event.uuid).flatMap(_.undeprecate(e.id, project, cRev))
        }
      case _: ElasticSearchViewTagAdded     => IO.unit // TODO: Check if this is correct
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          =>
        logger
          .warn(i)(s"An incorrect revision has been provided for '${event.id}' in project '${event.project}'")
          .as(ImportStatus.Dropped)
      case other                    => IO.raiseError(other)
    },
    _ => IO.pure(ImportStatus.Success)
  )

}

object ElasticSearchViewProcessor {

  private val logger = Logger[ElasticSearchViewProcessor]

  def apply(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      projectMapper: ProjectMapper,
      viewPatcher: ViewPatcher,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  ): ElasticSearchViewProcessor = {
    val views = (uuid: UUID) => ViewWiring.elasticSearchViews(fetchContext, rcr, config, clock, UUIDF.fixed(uuid), xas)
    new ElasticSearchViewProcessor(views, projectMapper, viewPatcher, clock)
  }

}
