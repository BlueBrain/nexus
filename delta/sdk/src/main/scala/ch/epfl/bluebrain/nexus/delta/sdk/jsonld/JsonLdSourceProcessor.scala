package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}

/**
  * Allows to define different JsonLd processorces
  */
sealed abstract class JsonLdSourceProcessor(implicit api: JsonLdApi) {

  def uuidF: UUIDF

  protected def getOrGenerateId(iri: Option[Iri], context: ProjectContext): UIO[Iri] =
    iri.fold(uuidF().map(uuid => context.base.iri / uuid.toString))(IO.pure)

  protected def expandSource(
      context: ProjectContext,
      source: Json
  )(implicit rcr: RemoteContextResolution): IO[InvalidJsonLdFormat, (ContextValue, ExpandedJsonLd)] = {
    implicit val opts: JsonLdOptions = JsonLdOptions(base = Some(context.base.iri))
    ExpandedJsonLd(source)
      .flatMap {
        case expanded if expanded.isEmpty && source.topContextValueOrEmpty.isEmpty =>
          val ctx = defaultCtx(context)
          ExpandedJsonLd(source.addContext(ctx.contextObj)).map(ctx -> _)
        case expanded                                                              =>
          UIO.pure(source.topContextValueOrEmpty -> expanded)
      }
      .mapError(err => InvalidJsonLdFormat(None, err))
  }

  protected def checkAndSetSameId(iri: Iri, expanded: ExpandedJsonLd): IO[UnexpectedId, ExpandedJsonLd] =
    expanded.rootId match {
      case _: BNode        => UIO.pure(expanded.replaceId(iri))
      case `iri`           => UIO.pure(expanded)
      case payloadIri: Iri => IO.raiseError(UnexpectedId(iri, payloadIri))
    }

  private def defaultCtx(context: ProjectContext): ContextValue =
    ContextObject(JsonObject(keywords.vocab -> context.vocab.asJson, keywords.base -> context.base.asJson))

}

object JsonLdSourceProcessor {

  /**
    * Allows to parse the given json source to JsonLD compacted and expanded using static contexts
    */
  final class JsonLdSourceParser[R](contextIri: Seq[Iri], override val uuidF: UUIDF)(implicit
      api: JsonLdApi,
      rejectionMapper: Mapper[InvalidJsonLdRejection, R]
  ) extends JsonLdSourceProcessor {

    /**
      * Converts the passed ''source'' to JsonLD compacted and expanded. The @id value is extracted from the payload.
      * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
      *
      * @param context
      *   the project context with the base used to generate @id when needed and the @context when not provided on the
      *   source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri, the compacted Json-LD and the expanded Json-LD
      */
    def apply(
        context: ProjectContext,
        source: Json
    )(implicit rcr: RemoteContextResolution): IO[R, (Iri, CompactedJsonLd, ExpandedJsonLd)] = {
      for {
        (ctx, originalExpanded) <- expandSource(context, source.addContext(contextIri: _*))
        iri                     <- getOrGenerateId(originalExpanded.rootId.asIri, context)
        expanded                 = originalExpanded.replaceId(iri)
        compacted               <- expanded.toCompacted(ctx).mapError(err => InvalidJsonLdFormat(Some(iri), err))
      } yield (iri, compacted, expanded)
    }.mapError(rejectionMapper.to)

    /**
      * Converts the passed ''source'' to JsonLD compacted and expanded. The @id value is extracted from the payload if
      * exists and compared to the passed ''iri''. If they aren't equal an [[UnexpectedId]] rejection is issued.
      *
      * @param context
      *   the project context to generate the @context when no @context is provided on the source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the compacted Json-LD and the expanded Json-LD
      */
    def apply(
        context: ProjectContext,
        iri: Iri,
        source: Json
    )(implicit
        rcr: RemoteContextResolution
    ): IO[R, (CompactedJsonLd, ExpandedJsonLd)] = {
      for {
        (ctx, originalExpanded) <- expandSource(context, source.addContext(contextIri: _*))
        expanded                <- checkAndSetSameId(iri, originalExpanded)
        compacted               <- expanded.toCompacted(ctx).mapError(err => InvalidJsonLdFormat(Some(iri), err))
      } yield (compacted, expanded)
    }.mapError(rejectionMapper.to)

  }

  /**
    * Allows to parse the given json source to JsonLD compacted and expanded using static and resolver-based contexts
    */
  final class JsonLdSourceResolvingParser[R](
      contextIri: Seq[Iri],
      contextResolution: ResolverContextResolution,
      override val uuidF: UUIDF
  )(implicit api: JsonLdApi, rejectionMapper: Mapper[InvalidJsonLdRejection, R])
      extends JsonLdSourceProcessor {

    private val underlying = new JsonLdSourceParser[R](contextIri, uuidF)

    /**
      * Converts the passed ''source'' to JsonLD compacted and expanded. The @id value is extracted from the payload.
      * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
      *
      * @param ref
      *   the project reference
      * @param context
      *   the project context with the base used to generate @id when needed and the @context when not provided on the
      *   source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri, the compacted Json-LD and the expanded Json-LD
      */
    def apply(ref: ProjectRef, context: ProjectContext, source: Json)(implicit
        caller: Caller
    ): IO[R, (Iri, CompactedJsonLd, ExpandedJsonLd)] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, source)
    }

    /**
      * Converts the passed ''source'' to JsonLD compacted and expanded. The @id value is extracted from the payload if
      * exists and compared to the passed ''iri''. If they aren't equal an [[UnexpectedId]] rejection is issued.
      *
      * @param ref
      *   the project reference
      * @param context
      *   the project context to generate the @context when no @context is provided on the source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the compacted Json-LD and the expanded Json-LD
      */
    def apply(
        ref: ProjectRef,
        context: ProjectContext,
        iri: Iri,
        source: Json
    )(implicit caller: Caller): IO[R, (CompactedJsonLd, ExpandedJsonLd)] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, iri, source)
    }

  }

  object JsonLdSourceResolvingParser {
    def apply[R](contextResolution: ResolverContextResolution, uuidF: UUIDF)(implicit
        api: JsonLdApi,
        rejectionMapper: Mapper[InvalidJsonLdRejection, R]
    ): JsonLdSourceResolvingParser[R] =
      new JsonLdSourceResolvingParser(Seq.empty, contextResolution, uuidF)
  }

  /**
    * Allows to parse the given json source and decode it into an ''A'' using static contexts
    */
  final class JsonLdSourceDecoder[R, A: JsonLdDecoder](contextIri: Iri, override val uuidF: UUIDF)(implicit
      api: JsonLdApi,
      rejectionMapper: Mapper[JsonLdRejection, R]
  ) extends JsonLdSourceProcessor {

    /**
      * Expands the passed ''source'' and attempt to decode it into an ''A'' The @id value is extracted from the
      * payload. When no @id is present, one is generated using the base on the project suffixed with a randomly
      * generated UUID.
      *
      * @param context
      *   the project context with the base used to generate @id when needed and the @context when not provided on the
      *   source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri and the decoded value
      */
    def apply(context: ProjectContext, source: Json)(implicit rcr: RemoteContextResolution): IO[R, (Iri, A)] = {
      for {
        (_, expanded) <- expandSource(context, source.addContext(contextIri))
        iri           <- getOrGenerateId(expanded.rootId.asIri, context)
        decodedValue  <- IO.fromEither(expanded.to[A]).mapError(DecodingFailed)
      } yield (iri, decodedValue)
    }.mapError(rejectionMapper.to)

    /**
      * Expands the passed ''source'' and attempt to decode it into an ''A'' The @id value is extracted from the payload
      * if exists and compared to the passed ''iri''. If they aren't equal an [[UnexpectedId]] rejection is issued.
      *
      * @param context
      *   the project context with the base used to generate @id when needed and the @context when not provided on the
      *   source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri and the decoded value
      */
    def apply(context: ProjectContext, iri: Iri, source: Json)(implicit
        rcr: RemoteContextResolution
    ): IO[R, A] = {
      for {
        (_, originalExpanded) <- expandSource(context, source.addContext(contextIri))
        expanded              <- checkAndSetSameId(iri, originalExpanded)
        decodedValue          <- IO.fromEither(expanded.to[A]).mapError(DecodingFailed)
      } yield decodedValue
    }.mapError(rejectionMapper.to)

  }

  /**
    * Allows to parse the given json source and decode it into an ''A'' using static and resolver-based contexts
    */
  final class JsonLdSourceResolvingDecoder[R, A: JsonLdDecoder](
      contextIri: Iri,
      contextResolution: ResolverContextResolution,
      override val uuidF: UUIDF
  )(implicit api: JsonLdApi, rejectionMapper: Mapper[JsonLdRejection, R])
      extends JsonLdSourceProcessor {

    private val underlying = new JsonLdSourceDecoder[R, A](contextIri, uuidF)

    /**
      * Expands the passed ''source'' and attempt to decode it into an ''A'' The @id value is extracted from the
      * payload. When no @id is present, one is generated using the base on the project suffixed with a randomly
      * generated UUID.
      *
      * @param ref
      *   the project reference
      * @param context
      *   the project context with the base used to generate @id when needed and the @context when not provided on the
      *   source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri and the decoded value
      */
    def apply(ref: ProjectRef, context: ProjectContext, source: Json)(implicit caller: Caller): IO[R, (Iri, A)] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, source)
    }

    /**
      * Expands the passed ''source'' and attempt to decode it into an ''A'' The @id value is extracted from the payload
      * if exists and compared to the passed ''iri''. If they aren't equal an [[UnexpectedId]] rejection is issued.
      *
      * @param ref
      *   the project reference
      * @param context
      *   the project context with with the base used to generate @id when needed and the @context when not provided on
      *   the source
      * @param source
      *   the Json payload
      * @return
      *   a tuple with the resulting @id iri and the decoded value
      */
    def apply(ref: ProjectRef, context: ProjectContext, iri: Iri, source: Json)(implicit caller: Caller): IO[R, A] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, iri, source)
    }
  }

}
