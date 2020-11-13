package ch.epfl.bluebrain.nexus.delta.sdk

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.{InvalidJsonLdFormat, UnexpectedResourceId}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import io.circe.Json
import monix.bio.IO

trait ResourceSourceParser {

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload.
    * When no @id is present, one is generated using the base on the project suffixed with a randomly generated UUID.
    *
    * @param project the project with the base used to generate @id when needed
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
      originalExpanded <- JsonLd.expand(source).leftMap(err => InvalidJsonLdFormat(None, err))
      base              = project.base.iri
      iri              <- originalExpanded.rootId.asIri.fold(uuidF().map(uuid => base / uuid.toString))(IO.pure)
      expanded          = originalExpanded.replaceId(iri)
      compacted        <- expanded.toCompacted(source).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (iri, compacted, expanded)

  /**
    * Converts the passed ''source'' to JsonLD compacted and expanded.
    * The @id value is extracted from the payload if exists and compared to the passed ''iri''.
    * If they aren't equal an [[UnexpectedResourceId]] rejection is issued.
    *
    * @param source the Json payload
    * @return a tuple with the compacted Json-LD and the expanded Json-LD
    */
  def asJsonLd(
      iri: Iri,
      source: Json
  )(implicit rcr: RemoteContextResolution): IO[ResourceRejection, (CompactedJsonLd, ExpandedJsonLd)] =
    for {
      originalExpanded <- JsonLd.expand(source).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
      _                <- checkSameId(iri, originalExpanded)
      expanded          = originalExpanded.replaceId(iri)
      compacted        <- expanded.toCompacted(source).leftMap(err => InvalidJsonLdFormat(Some(iri), err))
    } yield (compacted, expanded)

  private def checkSameId(iri: Iri, expanded: ExpandedJsonLd): IO[UnexpectedResourceId, Unit] =
    expanded.rootId.asIri match {
      case Some(sourceId) if sourceId != iri => IO.raiseError(UnexpectedResourceId(iri, sourceId))
      case _                                 => IO.unit
    }
}

object ResourceSourceParser extends ResourceSourceParser
