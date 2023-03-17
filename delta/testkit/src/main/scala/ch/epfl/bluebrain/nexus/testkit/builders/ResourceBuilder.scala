package ch.epfl.bluebrain.nexus.testkit.builders

import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json}

object ResourceBuilder {
  def resourceWith(id: String, typ: String): ResourceBuilder = {
    ResourceBuilder(id, typ)
  }
  sealed trait State
  object State {
    sealed trait Empty extends State
    sealed trait Valid extends State
  }
}

case class ResourceBuilder private (
    id: String,
    typ: String,
    prefixes: Map[String, String] = Map.empty,
    fields: Map[String, Json] = Map.empty
) {
  def withPrefixForNamespace(prefix: String, namespace: String): ResourceBuilder = {
    this.copy(prefixes = this.prefixes + (prefix -> namespace))
  }

  def withField[A: Encoder](name: String, value: A): ResourceBuilder = {
    this.copy(fields = this.fields + (name -> implicitly[Encoder[A]].apply(value)))
  }

  def build: Json = {
    val contextField = Option.when(prefixes.nonEmpty) {
      "@context" := Json.fromFields(
        prefixes.map { case (name, url) => name := url }
      )
    }

    Json.fromFields(
      List(
        "@id"   := id,
        "@type" := typ
      ) ++ fields ++ contextField
    )
  }
}
