package ch.epfl.bluebrain.nexus.ship.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.ship.{IriPatcher, ProjectMapper}
import io.circe.Json
import io.circe.optics.JsonPath.root
import io.circe.syntax.EncoderOps

final class ViewPatcher(projectMapper: ProjectMapper, iriPatcher: IriPatcher) {

  def patchAggregateViewSource(input: Json): Json =
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
    json
      .as[Iri]
      .map { iri =>
        iriPatcher(iri).asJson
      }
      .getOrElse(throw new IllegalArgumentException(s"Invalid view id found in aggregate view source: $json"))

}
