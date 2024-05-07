package ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext.StaticContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContext}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef.ProjectRemoteContextRef.ResourceContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution.ProjectRemoteContext
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Encoder, Json, JsonObject}

/**
  * Reference to a remote context
  */
sealed trait RemoteContextRef extends Product with Serializable {

  /**
    * The iri it is bound to
    */
  def iri: Iri
}

object RemoteContextRef {

  def apply(remoteContexts: Map[Iri, RemoteContext]): Set[RemoteContextRef] =
    remoteContexts.foldLeft(Set.empty[RemoteContextRef]) {
      case (acc, (input, _: StaticContext))              => acc + StaticContextRef(input)
      case (acc, (input, context: ProjectRemoteContext)) =>
        acc + ProjectRemoteContextRef(input, ResourceContext(context.iri, context.project, context.rev))
      case (_, (_, context))                             =>
        throw new NotImplementedError(s"Case for '${context.getClass.getSimpleName}' has not been implemented.")
    }

  /**
    * A reference to a static remote context
    */
  final case class StaticContextRef(iri: Iri) extends RemoteContextRef

  /**
    * A reference to a context registered in a Nexus project
    * @param iri
    *   the resolved iri
    * @param resource
    *   the qualified reference to the Nexus resource
    */
  final case class ProjectRemoteContextRef(iri: Iri, resource: ResourceContext) extends RemoteContextRef

  object ProjectRemoteContextRef {
    final case class ResourceContext(id: Iri, project: ProjectRef, rev: Int)
  }

  implicit val coder: Codec.AsObject[RemoteContextRef] = {
    implicit val configuration: Configuration = Serializer.circeConfiguration
    implicit val resourceContextRefCoder      = deriveConfiguredCodec[ResourceContext]
    deriveConfiguredCodec[RemoteContextRef]
  }

  implicit final val remoteContextRefsJsonLdEncoder: JsonLdEncoder[Set[RemoteContextRef]] = {
    implicit val remoteContextsEncoder: Encoder.AsObject[Set[RemoteContextRef]] = Encoder.AsObject.instance {
      remoteContexts =>
        JsonObject("remoteContexts" -> Json.arr(remoteContexts.map(_.asJson).toSeq: _*))
    }
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.remoteContexts))
  }
}
