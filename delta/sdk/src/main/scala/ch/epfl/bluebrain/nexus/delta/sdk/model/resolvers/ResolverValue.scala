package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, Configuration => JsonLdConfiguration}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

import scala.annotation.nowarn

sealed trait ResolverValue extends Product with Serializable {

  /**
    * @return resolution priority when attempting to find a resource
    */
  def priority: Priority

  /**
    * @return the resolver type
    */
  def tpe: ResolverType

}

object ResolverValue {

  /**
    * Necessary values to use a cross-project resolver
    * @param priority      resolution priority when attempting to find a resource
    */
  final case class InProjectValue(priority: Priority) extends ResolverValue {

    /**
      * @return the resolver type
      */
    override def tpe: ResolverType = ResolverType.InProject
  }

  /**
    * Necessary values to use a cross-project resolver
    *
    * @param priority           resolution priority when attempting to find a resource
    * @param resourceTypes      the resource types that will be accessible through this resolver
    *                           if empty, no restriction on resource type will be applied
    * @param projects           references to projects where the resolver will attempt to access
    *                           resources
    * @param identityResolution identities allowed to use this resolver
    */
  final case class CrossProjectValue(
      priority: Priority,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identityResolution: IdentityResolution
  ) extends ResolverValue {

    /**
      * @return the resolver type
      */
    override def tpe: ResolverType = ResolverType.CrossProject
  }

  /**
    * Generate the value payload from an id and a value
    * @param id             the id of the resolver
    * @param resolverValue  the value to encode as Json
    */
  def generateSource(id: Iri, resolverValue: ResolverValue): Json = {
    implicit val identityEncoder: Encoder[Identity] = Identity.persistIdentityDecoder
    resolverValue.asJson
      .deepMerge(
        Json.obj(
          keywords.id  -> id.asJson,
          keywords.tpe -> resolverValue.tpe.types.map(_.stripPrefix(nxv.base)).asJson
        )
      )
  }

  implicit private[resolvers] def resolverValueEncoder(implicit
      identityEncoder: Encoder[Identity]
  ): Encoder.AsObject[ResolverValue] = Encoder.AsObject.instance {
    case InProjectValue(priority)                                                 =>
      JsonObject(
        "priority" -> priority.asJson
      )
    case CrossProjectValue(priority, resourceTypes, projects, identityResolution) =>
      JsonObject(
        "priority"      -> priority.asJson,
        "resourceTypes" -> resourceTypes.asJson,
        "projects"      -> projects.asJson
      ).deepMerge(identityResolution.asJsonObject)
  }

  sealed private trait Resolver
  private case class InProject(priority: Priority) extends Resolver
  private case class CrossProject(
      priority: Priority,
      resourceTypes: Set[Iri] = Set.empty,
      projects: NonEmptyList[ProjectRef],
      useCurrentCaller: Boolean = false,
      identities: Option[Set[Identity]]
  )                                                extends Resolver

  @nowarn("cat=unused")
  implicit val resolverValueJsonLdDecoder: JsonLdDecoder[ResolverValue] = {
    implicit val config: JsonLdConfiguration              = JsonLdConfiguration.default
    implicit val identityDecoder: JsonLdDecoder[Identity] = deriveConfigJsonLdDecoder[Identity]
      .or(deriveConfigJsonLdDecoder[User].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Group].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Authenticated].asInstanceOf[JsonLdDecoder[Identity]])
    val resolverDecoder                                   = deriveConfigJsonLdDecoder[Resolver]

    (cursor: ExpandedJsonLdCursor) =>
      resolverDecoder(cursor).flatMap {
        case InProject(priority)                                                      => Right(InProjectValue(priority))
        case CrossProject(priority, resourceTypes, projects, true, None)              =>
          Right(CrossProjectValue(priority, resourceTypes, projects, UseCurrentCaller))
        case CrossProject(priority, resourceTypes, projects, false, Some(identities)) =>
          Right(CrossProjectValue(priority, resourceTypes, projects, ProvidedIdentities(identities)))
        case CrossProject(_, _, _, _, _)                                              =>
          Left(ParsingFailure("Only 'useCurrentCaller' or 'identities' should be defined"))
      }
  }

}
