package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Triple.predicate
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingDataGen
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.syntax.EncoderOps
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DataConstructQuerySpec
    extends AnyWordSpec
    with CirceLiteral
    with IOValues
    with Matchers
    with TestHelpers
    with OptionValues
    with EitherValuable {

  implicit private val cl: ClassLoader      = getClass.getClassLoader
  implicit val baseUri: BaseUri             = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit val res: RemoteContextResolution = RemoteContextResolution.fixed(
    Vocabulary.contexts.metadata -> ContextValue.fromFile("contexts/metadata.json").accepted
  )

  private val project = ProjectRef.unsafe("org", "proj")

  private val source = jsonContentOf("resource/source.json")

  private val data = IndexingDataGen
    .fromDataResource(
      nxv + "id",
      project,
      source
    )
    .accepted

  private def config(query: String) = ExpandedJsonLd
    .expanded(
      json"""[{ "https://bluebrain.github.io/nexus/vocabulary/query": [{ "@value" : ${query.asJson} }] }]"""
    )
    .rightValue

  "DataConstructQuery" should {

    "reject an invalid config" in {
      DataConstructQuery.value.parseAndRun(Some(ExpandedJsonLd.empty), data).rejected
    }

    "transform the data according to the query" in {
      val query  = """prefix nxv: <https://bluebrain.github.io/nexus/vocabulary/>
                    |
                    |CONSTRUCT {
                    |  ?person 	        nxv:name             ?name ;
                    |                   nxv:number           ?number ;
                    |} WHERE {
                    |  ?person 	        nxv:name             ?name ;
                    |                   nxv:number           ?number ;
                    |}""".stripMargin
      val name   = predicate(nxv + "name")
      val number = predicate(nxv + "number")
      DataConstructQuery.value
        .parseAndRun(Some(config(query)), data)
        .accepted
        .value shouldEqual data.copy(
        types = Set.empty,
        graph = data.graph.filter { case (_, p, _) =>
          p == name || p == number
        }
      )
    }
  }
}
