package ch.epfl.bluebrain.nexus.ship.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax.EncoderOps

final class ViewPatcher(projectMapper: ProjectMapper, iriPatcher: IriPatcher) {

  private def patchGenericViewResourceTypes(input: Json): Json =
    root.resourceTypes.arr.each.modify(patchResourceType)(input)

  private def patchResourceType(json: Json) =
    patchIri(json)
      .getOrElse(
        throw new IllegalArgumentException(s"Invalid resource type found in Blazegraph view resource types: $json")
      )

  private def patchIri(json: Json) = {
    json
      .as[Iri]
      .map { iri =>
        iriPatcher(iri).asJson
      }
  }

  def patchBlazegraphViewSource(input: Json): Json = {
    patchGenericViewResourceTypes(
      patchAggregateViewSource(input)
    )
  }

  def patchElasticSearchViewSource(input: Json): Json = {
    patchPipelineResourceTypes(
      patchGenericViewResourceTypes(
        patchAggregateViewSource(input)
      )
    )
  }

  private def patchPipelineResourceTypes(input: Json): Json = {
    root.pipeline.each.config.each.`https://bluebrain.github.io/nexus/vocabulary/types`.each.`@id`.string
      .modify(patchStringIri)(input)
  }

  private def patchStringIri(stringIri: String): String = {
    Iri.apply(stringIri).map(iriPatcher.apply).map(_.toString).getOrElse(stringIri)
  }

  private def patchAggregateViewSource(input: Json): Json =
    root.views.each.obj.modify { view =>
      view
        .mapAllKeys("project", patchProject)
        .mapAllKeys("viewId", patchViewId)
    }(input)

  private def patchProject(json: Json) =
    json
      .as[ProjectRef]
      .map { p =>
        projectMapper.map(p).asJson
      }
      .getOrElse(throw new IllegalArgumentException(s"Invalid project ref found in aggregate view source: $json"))

  private def patchViewId(json: Json) =
    patchIri(json)
      .getOrElse(throw new IllegalArgumentException(s"Invalid view id found in aggregate view source: $json"))

}
