package ch.epfl.bluebrain.nexus.kg.routes

import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.kg.config.Contexts.{resourceCtx, resourceCtxUri}
import ch.epfl.bluebrain.nexus.kg.resources.{Resource, ResourceV}
import ch.epfl.bluebrain.nexus.kg.resources.Views._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat.{Compacted, Expanded}
import ch.epfl.bluebrain.nexus.rdf.Graph
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json

object ResourceEncoder {

  def json(r: Resource)(implicit config: AppConfig, project: ProjectResource): Either[String, Json] =
    Graph(r.id.value, r.metadata()).toJson(resourceCtx).map(_.replaceContext(resourceCtxUri))

  def json(res: ResourceV)(implicit output: JsonLDOutputFormat): Either[String, Json] =
    output match {
      case Compacted => jsonCompacted(res)
      case Expanded  => jsonExpanded(res)
    }

  private val resourceKeys: List[String] = resourceCtx.contextValue.asObject.map(_.keys.toList).getOrElse(List.empty)

  private def jsonCompacted(res: ResourceV): Either[String, Json] = {
    val flattenedContext = Json.obj("@context" -> res.value.ctx) mergeContext resourceCtx
    res.value.graph.toJson(flattenedContext).map { fieldsJson =>
      val contextJson =
        Json.obj("@context" -> res.value.source.contextValue.removeKeys(resourceKeys: _*)).addContext(resourceCtxUri)
      val json        = fieldsJson deepMerge contextJson
      if (res.types.contains(nxv.View.value)) transformFetch(json)
      else json
    }
  }

  private def jsonExpanded(r: ResourceV): Either[String, Json] =
    r.value.graph.toJson()
}
