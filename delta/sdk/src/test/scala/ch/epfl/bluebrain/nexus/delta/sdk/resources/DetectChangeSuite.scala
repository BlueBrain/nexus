package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef.StaticContextRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

class DetectChangeSuite extends NexusSuite {

  implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient

  private val id = nxv + "id"

  private val source                                = Json.obj("source" := "value")
  private val compacted                             = CompactedJsonLd.unsafe(id, ContextValue(nxv + "context"), JsonObject("field" := "value"))
  private val remoteContexts: Set[RemoteContextRef] = Set(StaticContextRef(nxv + "static"))

  private val jsonld = JsonLdAssembly(
    nxv + "id",
    source,
    compacted,
    ExpandedJsonLd.empty,
    Graph.empty(id),
    remoteContexts
  )

  private val detectChange = DetectChange(enabled = true)

  test("No change is detected") {
    detectChange(jsonld, source, compacted, remoteContexts).assertEquals(false)
  }

  test("A change is detected if the remote contexts are different") {
    val otherRemoteContexts: Set[RemoteContextRef] = Set(StaticContextRef(nxv + "another-static"))
    detectChange(jsonld, source, compacted, otherRemoteContexts).assertEquals(true)
  }

  test("A change is detected if the local contexts are different") {
    val otherLocalContext =
      CompactedJsonLd.unsafe(id, ContextValue(nxv + "another-context"), JsonObject("field" := "value"))
    detectChange(jsonld, source, otherLocalContext, remoteContexts).assertEquals(true)
  }

  test("A change is detected if the source differs") {
    val otherSource = Json.obj("source" := "another-value")
    detectChange(jsonld, otherSource, compacted, remoteContexts).assertEquals(true)
  }

}
