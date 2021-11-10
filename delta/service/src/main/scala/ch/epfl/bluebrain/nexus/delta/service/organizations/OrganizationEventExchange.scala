package ch.epfl.bluebrain.nexus.delta.service.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{Organization, OrganizationEvent, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization.Metadata
import io.circe.syntax.EncoderOps
import monix.bio.{IO, UIO}

/**
  * Project specific [[EventExchange]] implementation for handling indexing/sse of organizations alongside its
  * resources.
  *
  * @param orgs
  *   the organizations module
  */
class OrganizationEventExchange(orgs: Organizations)(implicit base: BaseUri) extends EventExchange {

  override type E = OrganizationEvent
  override type A = Organization
  override type M = Metadata

  override def toJsonEvent(event: Event): Option[JsonValue.Aux[OrganizationEvent]] =
    event match {
      case ev: OrganizationEvent => Some(JsonValue(ev))
      case _                     => None
    }

  // TODO: Implement in further development
  override def toMetric(event: Event): UIO[Option[EventMetric]] = UIO.none

  override def toResource(event: Event, tag: Option[TagLabel]): UIO[Option[EventExchangeValue[A, M]]] =
    (event, tag) match {
      case (ev: OrganizationEvent, None) => resourceToValue(orgs.fetch(ev.organizationLabel))
      case _                             => UIO.none
    }

  private def resourceToValue(
      resourceIO: IO[OrganizationRejection, OrganizationResource]
  )(implicit enc: JsonLdEncoder[A], metaEnc: JsonLdEncoder[M]): UIO[Option[EventExchangeValue[A, M]]] =
    resourceIO
      .map { res =>
        Some(
          EventExchangeValue(ReferenceExchangeValue(res, res.value.asJson, enc), JsonLdValue(res.value.metadata), None)
        )
      }
      .onErrorHandle(_ => None)
}
