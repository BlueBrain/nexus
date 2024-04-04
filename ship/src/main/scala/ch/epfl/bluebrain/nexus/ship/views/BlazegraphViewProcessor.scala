package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, ValidateBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
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
    val cRev                = event.rev - 1
    val project             = projectMapper.map(event.project)
    event match {
      case e: BlazegraphViewCreated      => views(event.uuid).flatMap(_.create(e.id, project, e.value))
      case e: BlazegraphViewUpdated      => views(event.uuid).flatMap(_.update(e.id, project, cRev, e.value))
      case e: BlazegraphViewDeprecated   => views(event.uuid).flatMap(_.deprecate(e.id, project, cRev))
      case e: BlazegraphViewUndeprecated => views(event.uuid).flatMap(_.undeprecate(e.id, project, cRev))
      case _: BlazegraphViewTagAdded     => IO.unit // TODO: Can we tag?
    }
  }.redeemWith(
    {
      case a: ResourceAlreadyExists => logger.warn(a)("The resource already exists").as(ImportStatus.Dropped)
      case i: IncorrectRev          => logger.warn(i)("An incorrect revision has been provided").as(ImportStatus.Dropped)
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
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): BlazegraphViewProcessor = {
    val noValidation = new ValidateBlazegraphView {
      override def apply(value: BlazegraphViewValue): IO[Unit] = IO.unit
    }
    val prefix       = "nexus" // TODO: use the config?

    val views = (uuid: UUID) =>
      BlazegraphViews(
        fetchContext,
        rcr,
        noValidation,
        (_: ViewResource) => IO.unit,
        config,
        prefix,
        xas,
        clock
      )(jsonLdApi, UUIDF.fixed(uuid))
    new BlazegraphViewProcessor(views, projectMapper, clock)
  }

}
