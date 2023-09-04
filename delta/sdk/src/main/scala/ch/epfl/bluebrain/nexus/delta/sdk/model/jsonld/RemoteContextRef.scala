package ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext.StaticContext
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef.NexusContextRef.ResourceContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution.NexusContext
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import scala.annotation.nowarn

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
      case (acc, (input, _: StaticContext))      => acc + StaticContextRef(input)
      case (acc, (input, context: NexusContext)) =>
        acc + NexusContextRef(input, ResourceContext(context.iri, context.project, context.rev))
      case (_, (_, context))                     =>
        throw new NotImplementedError(s"Case for '${context.getClass.getSimpleName}' has not been implemented.")
    }

  /**
    * A reference to a static remote context
    */
  final case class StaticContextRef(iri: Iri) extends RemoteContextRef

  /**
    * A reference to a context registered in Nexus
    * @param id
    *   the identifier it has been resolved to
    * @param resource
    *   the qualified reference to the revisioned Nexus resource
    */
  final case class NexusContextRef(iri: Iri, resource: ResourceContext) extends RemoteContextRef

  object NexusContextRef {
    final case class ResourceContext(id: Iri, project: ProjectRef, rev: Int)
  }

  @nowarn("cat=unused")
  implicit val coder: Codec.AsObject[RemoteContextRef] = {
    implicit val configuration: Configuration = Serializer.circeConfiguration
    implicit val resourceContextRefCoder      = deriveConfiguredCodec[ResourceContext]
    deriveConfiguredCodec[RemoteContextRef]
  }
}
