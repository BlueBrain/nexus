package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import scala.annotation.nowarn

/**
  * Contextual information about a [[Projection]] element.
  */
sealed trait ElemCtx extends Product with Serializable {

  /**
    * @return
    *   the id of the source from which the elements with this context originates
    */
  def source: Iri
}

object ElemCtx {

  /**
    * Elements with this context originate from a [[Source]] with the `source` id.
    * @param source
    *   the id of the source from which the elements with this context originates
    */
  final case class SourceId(source: Iri) extends ElemCtx

  /**
    * Elements with this context originate from a [[Source]] with the `source` id and have been processed by an inner
    * pipe chain with the `pipeChain` id.
    *
    * @param source
    *   the id of the source from which the elements with this context originates
    * @param pipeChain
    *   the id of the inner pipe chain that processed this element
    */
  final case class SourceIdPipeChainId(source: Iri, pipeChain: Iri) extends ElemCtx

  implicit final val elemCtxCodec: Codec[ElemCtx] = {
    @nowarn("cat=unused")
    implicit val configuration: Configuration =
      Configuration.default.withDiscriminator(keywords.tpe)
    deriveConfiguredCodec[ElemCtx]
  }
}
