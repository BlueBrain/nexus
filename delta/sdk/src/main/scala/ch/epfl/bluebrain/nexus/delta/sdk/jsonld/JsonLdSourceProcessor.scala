package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.{ExplainResult, RdfError}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax._
import io.circe.{Json, JsonObject}

/**
  * Allows to define different JsonLd processors
  */
sealed abstract class JsonLdSourceProcessor(implicit api: JsonLdApi) {

  def uuidF: UUIDF

  protected def getOrGenerateId(iri: Option[Iri], context: ProjectContext): IO[Iri] =
    iri.fold(uuidF().map(uuid => context.base.iri / uuid.toString))(IO.pure)

  protected def expandSource(
      context: ProjectContext,
      source: Json
  )(implicit rcr: RemoteContextResolution): IO[(ContextValue, ExplainResult[ExpandedJsonLd])] = {
    implicit val opts: JsonLdOptions = JsonLdOptions(base = Some(context.base.iri))
    ExpandedJsonLd
      .explain(source)
      .flatMap {
        case result if result.value.isEmpty && source.topContextValueOrEmpty.isEmpty =>
          val ctx = defaultCtx(context)
          ExpandedJsonLd.explain(source.addContext(ctx.contextObj)).map(ctx -> _)
        case result                                                                  =>
          IO.pure(source.topContextValueOrEmpty -> result)
      }
      .adaptError { case err: RdfError => InvalidJsonLdFormat(None, err) }
  }

  protected def checkAndSetSameId(iri: Iri, expanded: ExpandedJsonLd): IO[ExpandedJsonLd] =
    expanded.rootId match {
      case _: BNode        => IO.pure(expanded.replaceId(iri))
      case `iri`           => IO.pure(expanded)
      case payloadIri: Iri => IO.raiseError(UnexpectedId(iri, payloadIri))
    }

  protected def validateIdNotBlank(source: Json): IO[Unit] =
    IO.raiseWhen(source.hcursor.downField("@id").as[String].exists(_.isBlank))(BlankId)

  private def defaultCtx(context: ProjectContext): ContextValue =
    ContextObject(JsonObject(keywords.vocab -> context.vocab.asJson, keywords.base -> context.base.asJson))

}

object JsonLdSourceProcessor {

  final case class JsonLdResult(
      iri: Iri,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      remoteContexts: Map[Iri, RemoteContext]
  ) {

    /**
      * The collection of known types
      */
    def types: Set[Iri] = expanded.getTypes.getOrElse(Set.empty)

    /**
      * The references for the remote contexts
      */
    def remoteContextRefs: Set[RemoteContextRef] = RemoteContextRef(remoteContexts)
  }

  /**
    * Allows to parse the given json source to JsonLD compacted and expanded using static contexts
    */
  final class JsonLdSourceParser[R <: Throwable](
      contextIri: Seq[Iri],
      override val uuidF: UUIDF
  )(implicit
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
    )(implicit rcr: RemoteContextResolution): IO[JsonLdResult] = {
      for {
        _               <- validateIdNotBlank(source)
        (ctx, result)   <- expandSource(context, source.addContext(contextIri: _*))
        originalExpanded = result.value
        iri             <- getOrGenerateId(originalExpanded.rootId.asIri, context)
        expanded         = originalExpanded.replaceId(iri)
        compacted       <- expanded.toCompacted(ctx).adaptError { case err: RdfError => InvalidJsonLdFormat(Some(iri), err) }
      } yield JsonLdResult(iri, compacted, expanded, result.remoteContexts)
    }.adaptError { case r: InvalidJsonLdRejection => rejectionMapper.to(r) }

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
    ): IO[JsonLdResult]                                        = {
      for {
        _               <- validateIdNotBlank(source)
        (ctx, result)   <- expandSource(context, source.addContext(contextIri: _*))
        originalExpanded = result.value
        expanded        <- checkAndSetSameId(iri, originalExpanded)
        compacted       <- expanded.toCompacted(ctx).adaptError { case err: RdfError => InvalidJsonLdFormat(Some(iri), err) }
      } yield JsonLdResult(iri, compacted, expanded, result.remoteContexts)
    }.adaptError { case r: InvalidJsonLdRejection => rejectionMapper.to(r) }

  }

  /**
    * Allows to parse the given json source to JsonLD compacted and expanded using static and resolver-based contexts
    */
  final class JsonLdSourceResolvingParser[R <: Throwable](
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
    def apply(ref: ProjectRef, context: ProjectContext, source: Json)(implicit caller: Caller): IO[JsonLdResult] = {
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
    )(implicit caller: Caller): IO[JsonLdResult] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, iri, source)
    }

    /**
      * Converts the passed ''source'' to JsonLD compacted and expanded. The @id value is extracted from the payload if
      * exists and compared to the passed ''iri'' if defined. If they aren't equal an [[UnexpectedId]] rejection is
      * issued.
      *
      * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
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
    def apply(ref: ProjectRef, context: ProjectContext, iriOpt: Option[Iri], source: Json)(implicit
        caller: Caller
    ): IO[JsonLdResult] =
      iriOpt
        .map { iri =>
          apply(ref, context, iri, source)
        }
        .getOrElse {
          apply(ref, context, source)
        }
  }

  object JsonLdSourceResolvingParser {
    def apply[R <: Throwable](contextResolution: ResolverContextResolution, uuidF: UUIDF)(implicit
        api: JsonLdApi,
        rejectionMapper: Mapper[InvalidJsonLdRejection, R]
    ): JsonLdSourceResolvingParser[R] =
      new JsonLdSourceResolvingParser(Seq.empty, contextResolution, uuidF)
  }

  /**
    * Allows to parse the given json source and decode it into an ''A'' using static contexts
    */
  final class JsonLdSourceDecoder[R <: Throwable, A: JsonLdDecoder](contextIri: Iri, override val uuidF: UUIDF)(implicit
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
    def apply(context: ProjectContext, source: Json)(implicit rcr: RemoteContextResolution): IO[(Iri, A)] = {
      for {
        (_, result)  <- expandSource(context, source.addContext(contextIri))
        expanded      = result.value
        iri          <- getOrGenerateId(expanded.rootId.asIri, context)
        decodedValue <- IO.fromEither(expanded.to[A].leftMap(DecodingFailed))
      } yield (iri, decodedValue)
    }.adaptError { case r: JsonLdRejection => rejectionMapper.to(r) }

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
    ): IO[A]                                                                                              = {
      for {
        (_, result)     <- expandSource(context, source.addContext(contextIri))
        originalExpanded = result.value
        expanded        <- checkAndSetSameId(iri, originalExpanded)
        decodedValue    <- IO.fromEither(expanded.to[A].leftMap(DecodingFailed))
      } yield decodedValue
    }.adaptError { case r: JsonLdRejection => rejectionMapper.to(r) }

  }

  /**
    * Allows to parse the given json source and decode it into an ''A'' using static and resolver-based contexts
    */
  final class JsonLdSourceResolvingDecoder[R <: Throwable, A: JsonLdDecoder](
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
    def apply(ref: ProjectRef, context: ProjectContext, source: Json)(implicit caller: Caller): IO[(Iri, A)] = {
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
    def apply(ref: ProjectRef, context: ProjectContext, iri: Iri, source: Json)(implicit caller: Caller): IO[A] = {
      implicit val rcr: RemoteContextResolution = contextResolution(ref)
      underlying(context, iri, source)
    }
  }

}
