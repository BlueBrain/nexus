package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.configuration.semiauto.deriveConfigJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{Configuration as JsonLdConfiguration, JsonLdDecoder}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.syntax.*
import io.circe.{Encoder, Json, JsonObject}

sealed trait ResolverValue extends Product with Serializable {

  /**
    * @return
    *   the resolver name
    */
  def name: Option[String]

  /**
    * @return
    *   the resolver description
    */
  def description: Option[String]

  /**
    * @return
    *   resolution priority when attempting to find a resource
    */
  def priority: Priority

  /**
    * @return
    *   the resolver type
    */
  def tpe: ResolverType

}

object ResolverValue {

  /**
    * InProject resolver value.
    *
    * @param priority
    *   resolution priority when attempting to find a resource
    */
  final case class InProjectValue(
      name: Option[String],
      description: Option[String],
      priority: Priority
  ) extends ResolverValue {

    /**
      * @return
      *   the resolver type
      */
    override def tpe: ResolverType = ResolverType.InProject
  }

  object InProjectValue {

    /**
      * @return
      *   an [[InProjectValue]] without name and description
      */
    def apply(priority: Priority): InProjectValue =
      InProjectValue(None, None, priority)
  }

  /**
    * Necessary values to use a cross-project resolver
    *
    * @param priority
    *   resolution priority when attempting to find a resource
    * @param resourceTypes
    *   the resource types that will be accessible through this resolver if empty, no restriction on resource type will
    *   be applied
    * @param projects
    *   references to projects where the resolver will attempt to access resources
    * @param identityResolution
    *   identities allowed to use this resolver
    */
  final case class CrossProjectValue(
      name: Option[String],
      description: Option[String],
      priority: Priority,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identityResolution: IdentityResolution
  ) extends ResolverValue {

    /**
      * @return
      *   the resolver type
      */
    override def tpe: ResolverType = ResolverType.CrossProject
  }

  object CrossProjectValue {

    /**
      * @return
      *   an [[CrossProjectValue]] without name and description
      */
    def apply(
        priority: Priority,
        resourceTypes: Set[Iri],
        projects: NonEmptyList[ProjectRef],
        identityResolution: IdentityResolution
    ): CrossProjectValue =
      CrossProjectValue(None, None, priority, resourceTypes, projects, identityResolution)
  }

  /**
    * Generate the value payload from an id and a value
    * @param id
    *   the id of the resolver
    * @param resolverValue
    *   the value to encode as Json
    */
  def generateSource(id: Iri, resolverValue: ResolverValue): Json = {
    implicit val identityEncoder: Encoder[Identity] = Identity.Database.identityCodec
    resolverValue.asJson
      .deepMerge(
        Json.obj(
          keywords.id  -> id.asJson,
          keywords.tpe -> resolverValue.tpe.types.map(_.stripPrefix(nxv.base)).asJson
        )
      )
      .deepDropNullValues
  }

  implicit private[resolvers] def resolverValueEncoder(implicit
      identityEncoder: Encoder[Identity]
  ): Encoder.AsObject[ResolverValue] =
    Encoder.AsObject.instance {
      case InProjectValue(name, description, priority)                                                 =>
        JsonObject(
          "name"        -> name.asJson,
          "description" -> description.asJson,
          "priority"    -> priority.asJson
        )
      case CrossProjectValue(name, description, priority, resourceTypes, projects, identityResolution) =>
        JsonObject(
          "name"          -> name.asJson,
          "description"   -> description.asJson,
          "priority"      -> priority.asJson,
          "resourceTypes" -> resourceTypes.asJson,
          "projects"      -> projects.asJson
        ).deepMerge(identityResolution.asJsonObject)
    }

  sealed private trait Resolver
  private case class InProject(
      name: Option[String],
      description: Option[String],
      priority: Priority
  ) extends Resolver

  private case class CrossProject(
      name: Option[String],
      description: Option[String],
      priority: Priority,
      resourceTypes: Set[Iri] = Set.empty,
      projects: NonEmptyList[ProjectRef],
      useCurrentCaller: Boolean = false,
      identities: Option[Set[Identity]]
  ) extends Resolver

  implicit val resolverValueJsonLdDecoder: JsonLdDecoder[ResolverValue] = {
    implicit val config: JsonLdConfiguration              = JsonLdConfiguration.default
    implicit val identityDecoder: JsonLdDecoder[Identity] = deriveConfigJsonLdDecoder[Identity]
      .or(deriveConfigJsonLdDecoder[User].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Group].asInstanceOf[JsonLdDecoder[Identity]])
      .or(deriveConfigJsonLdDecoder[Authenticated].asInstanceOf[JsonLdDecoder[Identity]])
    val resolverDecoder                                   = deriveConfigJsonLdDecoder[Resolver]

    (cursor: ExpandedJsonLdCursor) =>
      resolverDecoder(cursor).flatMap {
        case InProject(name, description, priority)                                                      =>
          Right(InProjectValue(name, description, priority))
        case CrossProject(name, description, priority, resourceTypes, projects, true, None)              =>
          Right(CrossProjectValue(name, description, priority, resourceTypes, projects, UseCurrentCaller))
        case CrossProject(name, description, priority, resourceTypes, projects, false, Some(identities)) =>
          Right(CrossProjectValue(name, description, priority, resourceTypes, projects, ProvidedIdentities(identities)))
        case CrossProject(_, _, _, _, _, _, _)                                                           =>
          Left(ParsingFailure("Only 'useCurrentCaller' or 'identities' should be defined"))
      }
  }

}
