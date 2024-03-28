package ch.epfl.bluebrain.nexus.ship.views

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{IncorrectRev, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewEvent, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, ValidateCompositeView}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.ship.views.CompositeViewProcessor.logger
import ch.epfl.bluebrain.nexus.ship.{EventClock, EventProcessor, ImportStatus}
import io.circe.Decoder

import java.util.UUID
import scala.concurrent.duration.DurationInt

class CompositeViewProcessor(views: UUID => IO[CompositeViews], clock: EventClock)
    extends EventProcessor[CompositeViewEvent] {
  override def resourceType: EntityType = CompositeViews.entityType

  override def decoder: Decoder[CompositeViewEvent] = CompositeViewEvent.serializer.codec

  override def evaluate(event: CompositeViewEvent): IO[ImportStatus] =
    for {
      _      <- clock.setInstant(event.instant)
      result <- evaluateInternal(event)
    } yield result

  private def evaluateInternal(event: CompositeViewEvent): IO[ImportStatus] = {
    implicit val s: Subject = event.subject
    implicit val c: Caller  = Caller(s, Set.empty)
    val cRev                = event.rev - 1

    event match {
      case e: CompositeViewCreated      => views(event.uuid).flatMap(_.create(e.project, e.source))
      case e: CompositeViewUpdated      => views(event.uuid).flatMap(_.update(e.id, e.project, cRev, e.source))
      case e: CompositeViewDeprecated   => views(event.uuid).flatMap(_.deprecate(e.id, e.project, cRev))
      case e: CompositeViewUndeprecated => views(event.uuid).flatMap(_.undeprecate(e.id, e.project, cRev))
      case _: CompositeViewTagAdded     => IO.unit // TODO: Can/should we tag?
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

object CompositeViewProcessor {

  private val logger = Logger[CompositeViewProcessor]

  def apply(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): CompositeViewProcessor = {
    val noValidation = new ValidateCompositeView {
      override def apply(uuid: UUID, value: CompositeViewValue): IO[Unit] = IO.unit
    }

    val views = (uuid: UUID) =>
      CompositeViews(
        fetchContext,
        rcr,
        noValidation,
        3.seconds,
        config,
        xas,
        clock
      )(jsonLdApi, UUIDF.fixed(uuid))

    new CompositeViewProcessor(views, clock)

  }

}
