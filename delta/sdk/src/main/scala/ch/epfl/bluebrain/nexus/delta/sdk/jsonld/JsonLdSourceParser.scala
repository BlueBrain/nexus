package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait JsonLdSourceParser {

  /**
    * Expand the given segment to an Iri using the provided project if necessary
    * @param segment the to translate to an Iri
    * @param project the project
    */
  def expandIri[R](segment: IdSegment, project: Project)(implicit
      rejectionMapper: Mapper[InvalidJsonLdRejection, R]
  ): IO[R, Iri] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base),
      rejectionMapper.to(InvalidId(segment.asString))
    )

  /**
    * Expands the passed ''source'' and attempt to decode it into an ''A''
    * The @id value is extracted from the payload.
    * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
    *
    * @param project the project with the base used to generate @id when needed and the @context when not provided on the source
    * @param source  the Json payload
    * @return a tuple with the resulting @id iri and the decode value
    */
  def decode[A: JsonLdDecoder, R](project: Project, source: Json)(implicit
      uuidF: UUIDF,
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, (Iri, A)] = {
    for {
      (_, expanded) <- expandSource(project, source)
      iri           <- getOrGenerateId(expanded.rootId.asIri, project)
      decodedValue  <- IO.fromEither(expanded.to[A].leftMap(DecodingFailed))
    } yield (iri, decodedValue)
  }.leftMap(rejectionMapper.to)

  /**
    * Expands the passed ''source'' and attempt to decode it into an ''A''
    * The @id value is extracted from the payload if exists and compared to the passed ''iri''.
    * If they aren't equal an [[UnexpectedId]] rejection is issued.
    *
    * @param project the project with the base used to generate @id when needed and the @context when not provided on the source
    * @param source  the Json payload
    * @return a tuple with the resulting @id iri and the decode value
    */
  def decode[A: JsonLdDecoder, R](project: Project, iri: Iri, source: Json)(implicit
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, A] = {
    for {
      (_, expanded) <- expandSource(project, source)
      _             <- checkAndSetSameId(iri, expanded)
      decodedValue  <- IO.fromEither(expanded.to[A].leftMap(DecodingFailed))
    } yield decodedValue
  }.leftMap(rejectionMapper.to)

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload.
    * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
    *
    * @param project the project with the base used to generate @id when needed and the @context when not provided on the source
    * @param source  the Json payload
    * @return a tuple with the resulting @id iri, the compacted Json-LD and the expanded Json-LD
    */
  def asJsonLd[R](
      project: Project,
      source: Json
  )(implicit
      uuidF: UUIDF,
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[InvalidJsonLdRejection, R]
  ): IO[R, (Iri, CompactedJsonLd, ExpandedJsonLd)] = {
    for {
      (ctx, originalExpanded) <- expandSource(project, source)
      iri                     <- getOrGenerateId(originalExpanded.rootId.asIri, project)
      expanded                 = originalExpanded.replaceId(iri)
      compacted               <- expanded.toCompacted(ctx).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (iri, compacted, expanded)
  }.leftMap(rejectionMapper.to)

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload if exists and compared to the passed ''iri''.
    * If they aren't equal an [[UnexpectedId]] rejection is issued.
    *
    * @param project the project used to generate the @context when no @context is provided on the source
    * @param source the Json payload
    * @return a tuple with the compacted Json-LD and the expanded Json-LD
    */
  def asJsonLd[R](
      project: Project,
      iri: Iri,
      source: Json
  )(implicit
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[InvalidJsonLdRejection, R]
  ): IO[R, (CompactedJsonLd, ExpandedJsonLd)] = {
    for {
      (ctx, originalExpanded) <- expandSource(project, source)
      expanded                <- checkAndSetSameId(iri, originalExpanded)
      compacted               <- expanded.toCompacted(ctx).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (compacted, expanded)
  }.leftMap(rejectionMapper.to)

  private def getOrGenerateId(iri: Option[Iri], project: Project)(implicit uuidF: UUIDF): UIO[Iri] =
    iri.fold(uuidF().map(uuid => project.base.iri / uuid.toString))(IO.pure)

  private def expandSource(
      project: Project,
      source: Json
  )(implicit rcr: RemoteContextResolution): IO[InvalidJsonLdFormat, (ContextValue, ExpandedJsonLd)] =
    ExpandedJsonLd(source)
      .flatMap {
        case expanded if expanded.isEmpty =>
          val ctx = defaultCtx(project)
          ExpandedJsonLd(source.addContext(ctx.contextObj)).map(ctx -> _)
        case expanded                     =>
          UIO.pure(source.topContextValueOrEmpty -> expanded)
      }
      .leftMap(err => InvalidJsonLdFormat(None, err))

  private def checkAndSetSameId(iri: Iri, expanded: ExpandedJsonLd): IO[UnexpectedId, ExpandedJsonLd] = {
    (expanded.changeRootIfExists(iri), expanded.rootId) match {
      case (Some(changedRootExpanded), _) => UIO.pure(changedRootExpanded)
      case (None, _: BNode)               => UIO.pure(expanded.replaceId(iri))
      case (None, payloadIri: Iri)        => IO.raiseError(UnexpectedId(iri, payloadIri))
    }
  }

  private def defaultCtx(project: Project): ContextValue =
    ContextValue.unsafe(Json.obj(keywords.vocab -> project.vocab.asJson, keywords.base -> project.base.asJson))

}

object JsonLdSourceParser extends JsonLdSourceParser
