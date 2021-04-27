package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, TagLabel}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}
import io.circe.syntax._

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of Resolver event types.
  */
sealed trait ResolverEvent extends ProjectScopedEvent {

  /**
    * @return the resolver identifier
    */
  def id: Iri

  /**
    * @return the project where the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return the resolver type
    */
  def tpe: ResolverType

}

object ResolverEvent {

  /**
    * Event for the creation of a resolver
    *
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param value   additional fields to configure the resolver
    * @param instant the instant this event was created
    * @param subject the subject which created this event
    */
  final case class ResolverCreated(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent {
    override val tpe: ResolverType = value.tpe
  }

  /**
    * Event for the modification of an existing resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param value     additional fields to configure the resolver
    * @param rev       the last known revision of the resolver
    * @param instant   the instant this event was created
    * @param subject   the subject which created this event
    */
  final case class ResolverUpdated(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent {
    override val tpe: ResolverType = value.tpe
  }

  /**
    * Event for to tag a resolver
    *
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param tpe       the resolver type
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''tagRev''
    * @param rev       the last known revision of the resolver
    * @param instant   the instant this event was created
    * @param subject   the subject creating this event
    */
  final case class ResolverTagAdded(
      id: Iri,
      project: ProjectRef,
      tpe: ResolverType,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the deprecation of a resolver
    *
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param tpe     the resolver type
    * @param rev     the last known revision of the resolver
    * @param instant the instant this event was created
    * @param subject the subject creating this event
    */
  final case class ResolverDeprecated(
      id: Iri,
      project: ProjectRef,
      tpe: ResolverType,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  private val context = ContextValue(contexts.metadata, contexts.resolvers)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"      => nxv.resolverId.prefix
      case "source"  => nxv.source.prefix
      case "project" => nxv.project.prefix
      case "rev"     => nxv.rev.prefix
      case "instant" => nxv.instant.prefix
      case "subject" => nxv.eventSubject.prefix
      case other     => other
    })

  @nowarn("cat=unused")
  implicit def resolverEventEncoder(implicit baseUri: BaseUri): Encoder.AsObject[ResolverEvent] = {
    implicit val subjectEncoder: Encoder[Subject]             = Identity.subjectIdEncoder
    implicit val identityEncoder: Encoder.AsObject[Identity]  = Identity.persistIdentityDecoder
    implicit val resolverValueEncoder: Encoder[ResolverValue] = Encoder.instance[ResolverValue](_ => Json.Null)
    Encoder.encodeJsonObject.contramapObject { resolver =>
      deriveConfiguredEncoder[ResolverEvent]
        .encodeObject(resolver)
        .remove("tpe")
        .add(nxv.types.prefix, resolver.tpe.types.asJson)
        .add(nxv.constrainedBy.prefix, schemas.resolvers.asJson)
        .add(keywords.context, context.value)
    }
  }
}
