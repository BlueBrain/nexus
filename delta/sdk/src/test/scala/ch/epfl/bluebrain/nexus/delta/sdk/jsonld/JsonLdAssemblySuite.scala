package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef.StaticContextRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

class JsonLdAssemblySuite extends NexusSuite {

  implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient

  private val id = nxv + "id"

  private val jsonld = JsonLdAssembly(
    nxv + "id",
    Json.obj("source" := "value"),
    CompactedJsonLd.unsafe(id, ContextValue(nxv + "context"), JsonObject("field" := "value")),
    ExpandedJsonLd.empty,
    Graph.empty(id),
    Set(StaticContextRef(nxv + "static"))
  )

  test("A jsonld assembly is equal to itself") {
    assert(jsonld === jsonld)
  }

  test("Two jsonld assemblies are not equals if the id is different") {
    val other = jsonld.copy(id = nxv + "another-id")
    assert(jsonld =!= other)
  }

  test("Two jsonld assemblies are not equals if the remote contexts are different") {
    val otherRemoteContexts: Set[RemoteContextRef] = Set(StaticContextRef(nxv + "another-static"))
    val other                                      = jsonld.copy(remoteContexts = otherRemoteContexts)
    assert(jsonld =!= other)
  }

  test("Two jsonld assemblies are not equals if the context defined in the compacted form differs") {
    val compacted = CompactedJsonLd.unsafe(id, ContextValue(nxv + "another-context"), JsonObject("field" := "value"))
    val other     = jsonld.copy(compacted = compacted)
    assert(jsonld =!= other)
  }

  test("Two jsonld assemblies are not equals if the context defined in the compacted form differs") {
    val compacted = CompactedJsonLd.unsafe(id, ContextValue(nxv + "another-context"), JsonObject("field" := "value"))
    val other     = jsonld.copy(compacted = compacted)
    assert(jsonld =!= other)
  }

  test("Two jsonld assemblies are not equals if the graph forms are not isomorphic") {
    val graph = Graph.empty(id).add(nxv + "new", "value")
    val other = jsonld.copy(graph = graph)
    assert(jsonld =!= other)
  }

  test("Two jsonld assemblies are not equals if the original source are different") {
    val source = Json.obj("source" := "another-value")
    val other  = jsonld.copy(source = source)
    assert(jsonld =!= other)
  }

}
