package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, BlazegraphViewEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship.views.BlazegraphViewProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus, ProjectMapper}
import io.circe.Decoder

import java.util.UUID

class BlazegraphViewProcessor private (
    views: UUID => IO[BlazegraphViews],
    projectMapper: ProjectMapper,
    viewPatcher: ViewPatcher,
    clock: EventClock
) extends EventProcessor[BlazegraphViewEvent] {

  override def resourceType: EntityType = BlazegraphViews.entityType

  override def decoder: Decoder[BlazegraphViewEvent] = BlazegraphViewEvent.serializer.codec

  override def evaluate(event: BlazegraphViewEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: BlazegraphViewEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1
    val project             = projectMapper.map(event.project)
    event match {
      case e: BlazegraphViewCreated      =>
        e.id match {
          case id if id == defaultViewId => IO.unit // the default view is created on project creation
          case _                         =>
            val patchedSource = viewPatcher.patchBlazegraphViewSource(e.source)
            views(event.uuid).flatMap(_.create(e.id, project, patchedSource))
        }
      case e: BlazegraphViewUpdated      =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         =>
            val patchedSource = viewPatcher.patchBlazegraphViewSource(e.source)
            views(event.uuid).flatMap(_.update(e.id, project, cRev, patchedSource))
        }
      case e: BlazegraphViewDeprecated   =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         => views(event.uuid).flatMap(_.deprecate(e.id, project, cRev))
        }
      case e: BlazegraphViewUndeprecated =>
        e.id match {
          case id if id == defaultViewId => IO.unit
          case _                         => views(event.uuid).flatMap(_.undeprecate(e.id, project, cRev))
        }
      case _: BlazegraphViewTagAdded     => IO.unit // TODO: Can we tag?
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

object BlazegraphViewProcessor {

  private val logger = Logger[BlazegraphViewProcessor]

  def apply(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      projectMapper: ProjectMapper,
      viewPatcher: ViewPatcher,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  ): BlazegraphViewProcessor = {
    val views = (uuid: UUID) => ViewWiring.blazegraphViews(fetchContext, rcr, config, clock, UUIDF.fixed(uuid), xas)
    new BlazegraphViewProcessor(views, projectMapper, viewPatcher, clock)
  }

}
