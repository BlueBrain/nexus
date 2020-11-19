package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{InvalidId, InvalidJsonLdFormat, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait JsonLdSourceParser {

  /**
    * Computes the id from the source or generate one from the project if none is provided
    * If both are provided, we make sure, they don't clash
    *
    * @param project the project
    * @param source  the Json payload
    */
  def computeId[R](project: Project, source: Json)(implicit
      uuidF: UUIDF,
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, Iri] =
    for {
      (_, expanded) <- expandSource(project, source).leftMap(rejectionMapper.to)
      iri           <- getOrGenerateId(expanded.rootId.asIri, project)
    } yield iri

  /**
    * Computes the id from the segment and validate it against the one in the source
    *
    * @param idSegment        the id provided in the segment
    * @param project          the project
    * @param source           the Json payload
    */
  def computeId[R](idSegment: IdSegment, project: Project, source: Json)(implicit
      rcr: RemoteContextResolution,
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, Iri] =
    for {
      (_, expanded) <- expandSource(project, source).leftMap(rejectionMapper.to)
      iri           <- expandIri(idSegment, project)
      _             <- checkSameId(iri, expanded).leftMap(rejectionMapper.to)
    } yield iri

  /**
    * Expand the given segment to an Iri using the provided project if necessary
    * @param segment the to translate to an Iri
    * @param project the project
    */
  def expandIri[R](segment: IdSegment, project: Project)(implicit
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, Iri] =
    IO.fromOption(
      segment.toIri(project.apiMappings, project.base),
      rejectionMapper.to(InvalidId(segment.asString))
    )

  /**
    * Return the iri if present or generate using the base on the project suffixed with a randomly generated UUID
    * @param iri     an optional iri
    * @param project the project with the base used to generate @id when needed
    */
  def getOrGenerateId(iri: Option[Iri], project: Project)(implicit uuidF: UUIDF): UIO[Iri] =
    iri.fold(uuidF().map(uuid => project.base.iri / uuid.toString))(IO.pure)

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
      rejectionMapper: Mapper[JsonLdRejection, R]
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
      rejectionMapper: Mapper[JsonLdRejection, R]
  ): IO[R, (CompactedJsonLd, ExpandedJsonLd)] = {
    for {
      (ctx, originalExpanded) <- expandSource(project, source)
      _                       <- checkSameId(iri, originalExpanded)
      expanded                 = originalExpanded.replaceId(iri)
      compacted               <- expanded.toCompacted(ctx).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (compacted, expanded)
  }.leftMap(rejectionMapper.to)

  private def expandSource(project: Project, source: Json)(implicit
      rcr: RemoteContextResolution
  ): IO[InvalidJsonLdFormat, (ContextValue, ExpandedJsonLd)] =
    JsonLd
      .expand(source)
      .flatMap {
        case expanded if expanded.isEmpty =>
          val ctx = defaultCtx(project)
          JsonLd.expand(source.addContext(ctx.contextObj)).map(ctx -> _)
        case expanded                     =>
          UIO.pure(source.topContextValueOrEmpty -> expanded)
      }
      .leftMap(err => InvalidJsonLdFormat(None, err))

  private def checkSameId(iri: Iri, expanded: ExpandedJsonLd): IO[UnexpectedId, Unit] =
    expanded.rootId.asIri match {
      case Some(sourceId) if sourceId != iri => IO.raiseError(UnexpectedId(iri, sourceId))
      case _                                 => IO.unit
    }

  private def defaultCtx(project: Project): ContextValue =
    ContextValue.unsafe(Json.obj(keywords.vocab -> project.vocab.asJson, keywords.base -> project.base.asJson))

}

object JsonLdSourceParser extends JsonLdSourceParser
