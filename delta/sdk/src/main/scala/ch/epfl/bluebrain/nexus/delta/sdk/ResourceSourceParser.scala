package ch.epfl.bluebrain.nexus.delta.sdk

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.{InvalidJsonLdFormat, UnexpectedResourceId}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait ResourceSourceParser {

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload.
    * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
    *
    * @param project the project with the base used to generate @id when needed and the @context when not provided on the source
    * @param source  the Json payload
    * @return a tuple with the resulting @id iri, the compacted Json-LD and the expanded Json-LD
    */
  def asJsonLd(
      project: Project,
      source: Json
  )(implicit
      uuidF: UUIDF,
      rcr: RemoteContextResolution
  ): IO[InvalidJsonLdFormat, (Iri, CompactedJsonLd, ExpandedJsonLd)] =
    for {
      (ctx, originalExpanded) <- expandSource(project, source)
      base                     = project.base.iri
      iri                     <- originalExpanded.rootId.asIri.fold(uuidF().map(uuid => base / uuid.toString))(IO.pure)
      expanded                 = originalExpanded.replaceId(iri)
      compacted               <- expanded.toCompacted(ctx).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (iri, compacted, expanded)

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload if exists and compared to the passed ''iri''.
    * If they aren't equal an [[UnexpectedResourceId]] rejection is issued.
    *
    * @param project the project used to generate the @context when no @context is provided on the source
    * @param source the Json payload
    * @return a tuple with the compacted Json-LD and the expanded Json-LD
    */
  def asJsonLd(
      project: Project,
      iri: Iri,
      source: Json
  )(implicit rcr: RemoteContextResolution): IO[ResourceRejection, (CompactedJsonLd, ExpandedJsonLd)] =
    for {
      (ctx, originalExpanded) <- expandSource(project, source)
      _                       <- checkSameId(iri, originalExpanded)
      expanded                 = originalExpanded.replaceId(iri)
      compacted               <- expanded.toCompacted(ctx).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (compacted, expanded)

  private def expandSource(project: Project, source: Json)(implicit
      rcr: RemoteContextResolution
  ): IO[InvalidJsonLdFormat, (ContextValue, ExpandedJsonLd)] =
    JsonLd.expand(source).leftMap(err => InvalidJsonLdFormat(None, err)).flatMap {
      case expanded if expanded.isEmpty =>
        val ctx = defaultCtx(project)
        JsonLd.expand(source.addContext(ctx.contextObj)).leftMap(err => InvalidJsonLdFormat(None, err)).map(ctx -> _)
      case expanded                     =>
        UIO.pure(source.topContextValueOrEmpty -> expanded)
    }

  private def checkSameId(iri: Iri, expanded: ExpandedJsonLd): IO[UnexpectedResourceId, Unit] =
    expanded.rootId.asIri match {
      case Some(sourceId) if sourceId != iri => IO.raiseError(UnexpectedResourceId(iri, sourceId))
      case _                                 => IO.unit
    }

  private def defaultCtx(project: Project): ContextValue =
    ContextValue.unsafe(Json.obj(keywords.vocab -> project.vocab.asJson, keywords.base -> project.base.asJson))

}

object ResourceSourceParser extends ResourceSourceParser
