package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContext}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution.ProjectRemoteContext
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import io.circe.{Json, JsonObject}

trait ResourceInstanceFixture extends CirceLiteral {

  val org: Label                      = Label.unsafe("myorg")
  val proj: Label                     = Label.unsafe("myproj")
  val projectRef: ProjectRef          = ProjectRef(org, proj)
  val myId: IriOrBNode.Iri            = nxv + "myId"
  val staticContext                   = iri"https://bluebrain.github.io/nexus/contexts/metadata.json"
  val nexusContext                    = iri"https://neuroshapes.org"
  val types                           = Set(iri"https://neuroshapes.org/Morphology")
  val source: Json                    =
    json"""
          {
            "@context": [
              "$nexusContext",
              "$staticContext",
              {"@vocab": "https://bluebrain.github.io/nexus/vocabulary/"}
            ],
            "@id": "$myId",
            "@type": "Morphology",
            "name": "Morphology 001"
          }
        """
  private val expandedObj: JsonObject =
    jobj"""
        {
          "@id" : "$myId",
          "@type" : [ "https://neuroshapes.org/Morphology" ],
          "https://bluebrain.github.io/nexus/vocabulary/name" : [ { "@value" : "Morphology 001" } ]
        }"""

  val expanded: ExpandedJsonLd = ExpandedJsonLd.unsafe(myId, expandedObj)
  private val compactedObj     =
    jobj"""
       {
        "@context": [
          "$nexusContext",
          "$staticContext",
          {"@vocab": "https://bluebrain.github.io/nexus/vocabulary/"}
         ],
         "@id": "$myId",
         "@type": "Morphology",
         "name": "Morphology 001"
       }"""

  val compacted: CompactedJsonLd               =
    CompactedJsonLd.unsafe(myId, compactedObj.topContextValueOrEmpty, compactedObj.remove(keywords.context))
  val remoteContexts: Map[Iri, RemoteContext]  = Map(
    staticContext -> StaticContext(staticContext, ContextValue.empty),
    nexusContext  -> ProjectRemoteContext(nexusContext, projectRef, 5, ContextValue.empty)
  )
  val remoteContextRefs: Set[RemoteContextRef] = RemoteContextRef(remoteContexts)

}
