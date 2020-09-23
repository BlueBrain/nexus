package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, RawJsonLdContext, RemoteContextResolution}
import io.circe.Json
import monix.bio.IO
import org.apache.jena.iri.IRI

trait JsonLdEncoder[A] { self =>

  /**
    * Converts a value to [[JsonLd]]
    */
  def apply(value: A): IO[RdfError, JsonLd]

  def contextValue: RawJsonLdContext

  /**
    * Creates an encoder composing two different encoders and a decomposition function ''f''.
    *
    * @param f the function to decomposed the value of the target encoder into values the passed encoders
    * @tparam B the generic type for values of the passed encoder
    * @tparam C the generic type for values of the target encoder
    */
  def compose[B, C](f: C => (A, B), fId: C => IRI)(implicit B: JsonLdEncoder[B]): JsonLdEncoder[C] =
    new JsonLdEncoder[C] {
      def apply(value: C): IO[RdfError, JsonLd] = {
        val (a, b) = f(value)
        val rootId = fId(value)
        (self.apply(a), B.apply(b))
          .mapN {
            case (ExpandedJsonLd(aObj, _), ExpandedJsonLd(bObj, _)) =>
              Some(ExpandedJsonLd(bObj deepMerge aObj, rootId))

            case (
                  CompactedJsonLd(aObj, aCtx @ RawJsonLdContext(_), _, _),
                  CompactedJsonLd(bObj, bCtx @ RawJsonLdContext(_), _, _)
                ) =>
              Some(CompactedJsonLd(bObj deepMerge aObj, aCtx.merge(bCtx), rootId, ContextFields.Skip))

            case _                                                  => None
          }
          .flatMap(IO.fromOption(_, UnexpectedJsonLd("Both JsonLdEncoders must produce the same JsonLd output format")))
      }

      override val contextValue: RawJsonLdContext = self.contextValue merge B.contextValue
    }

  private def contextObj: Json = Json.obj(keywords.context -> contextValue.value)

  def compact(a: A, contextObject: Json = contextObj)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd[RawJsonLdContext]] =
    for {
      ld        <- apply(a)
      compacted <- ld.toCompacted(contextObject, ContextFields.Skip)
    } yield compacted

  def expand(a: A)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    for {
      ld       <- apply(a)
      expanded <- ld.toExpanded
    } yield expanded

  def dot(a: A, contextObject: Json = contextObj)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Dot] =
    for {
      ld    <- apply(a)
      graph <- ld.toGraph
      dot   <- graph.toDot(contextObject)
    } yield dot

  def ntriples(a: A)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, NTriples] =
    for {
      ld       <- apply(a)
      graph    <- ld.toGraph
      ntriples <- graph.toNTriples
    } yield ntriples

}
