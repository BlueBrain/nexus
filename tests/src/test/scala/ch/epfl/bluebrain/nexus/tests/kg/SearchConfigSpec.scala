package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.resources.Rick
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Organizations, Resources}
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion

class SearchConfigSpec extends BaseSpec {

  private val orgId    = genId()
  private val projId1  = genId()
  private val id1      = s"$orgId/$projId1"
  private val projects = List(id1)

  private val neuronMorphologyId = "https://bbp.epfl.ch/data/neuron-morphology"
  private val neuronDensityId    = "https://bbp.epfl.ch/data/neuron-density"
  private val traceId            = "https://bbp.epfl.ch/data/trace"
  private val layerThicknessId   = "https://bbp.epfl.ch/data/layer-thickness"
  private val boutonDensityId    = "https://bbp.epfl.ch/data/bouton-density"

  // the resources that should appear in the search index
  private val mainResources  = List(
    "/kg/search/patched-cell.json",
    "/kg/search/trace.json",
    "/kg/search/neuron-morphology.json",
    "/kg/search/neuron-density.json",
    "/kg/search/layer-thickness.json",
    "/kg/search/bouton-density.json"
  )
  private val otherResources = List(
    "/kg/search/article.json",
    "/kg/search/org.json",
    "/kg/search/license.json",
    "/kg/search/activity.json",
    "/kg/search/protocol.json",
    "/kg/search/person.json"
  )
  private val allResources   = otherResources ++ mainResources

  override def beforeAll(): Unit = {
    super.beforeAll()

    val searchSetup = for {
      _ <- aclDsl.cleanAcls(Rick)
      _ <- aclDsl.addPermission("/", Rick, Organizations.Create)
      _ <- adminDsl.createOrganization(orgId, orgId, Rick)
      _ <- adminDsl.createProject(orgId, projId1, kgDsl.projectJson(path = "/kg/projects/bbp.json", name = id1), Rick)

      _ <- aclDsl.addPermission(s"/$orgId", Rick, Resources.Read)
      _ <- aclDsl.addPermission(s"/$orgId/$projId1", Rick, Resources.Read)

      _ <- postResource("/kg/search/neuroshapes.json")
      _ <- postResource("/kg/search/bbp-neuroshapes.json")
      _ <- allResources.traverseTap(postResource)
    } yield ()

    searchSetup.accepted
  }

  "search indexing" should {

    "index all data" in {
      eventually {
        deltaClient.post[Json]("/search/query", json"""{"size": 100}""", Rick) { (body, response) =>
          response.status shouldEqual StatusCodes.OK
          val sources = Json.fromValues(body.findAllByKey("_source"))
          sources.asArray.get.size shouldBe mainResources.size
        }
      }
    }

    "index project" in {
      val query    = queryField(neuronMorphologyId, "project")
      val expected =
        json"""
        {
          "project" : {
            "identifier" : "http://delta:8080/v1/projects/$id1",
            "label" : "$id1"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index license" in {
      val query    = queryField(neuronMorphologyId, "license")
      val expected =
        json"""
        {
          "license" : {
            "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/licenses/97521f71-605d-4f42-8f1b-c37e742a30bf",
            "label" : "SSCX Portal Data Licence final v1.0"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index brain region" in {
      val query    = queryField(neuronMorphologyId, "brainRegion")
      val expected =
        json"""
          {
            "brainRegion" : {
              "@id" : "http://purl.obolibrary.org/obo/UBERON_0008933",
              "idLabel" : "http://purl.obolibrary.org/obo/UBERON_0008933|primary somatosensory cortex",
              "identifier" : "http://purl.obolibrary.org/obo/UBERON_0008933",
              "label" : "primary somatosensory cortex"
            }
          }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index layers" in {
      val query    = queryField(neuronMorphologyId, "layer")
      val expected =
        json"""{
             "layer" : [
              {
                "@id" : "http://purl.obolibrary.org/obo/UBERON_0005391",
                "idLabel" : "http://purl.obolibrary.org/obo/UBERON_0005391|layer 2",
                "identifier" : "http://purl.obolibrary.org/obo/UBERON_0005391",
                "label" : "layer 2"
              }
            ]
          }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }.accepted
    }

    "index coordinatedInBrainAtlas" in {
      val query    = queryField(neuronMorphologyId, "coordinatesInBrainAtlas")
      val expected =
        json"""
        {
          "coordinatesInBrainAtlas" : {
            "valueX" : "7124.0",
            "valueY" : "1040.05",
            "valueZ" : "5129.275"
          }
        }
        """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }.accepted
    }

    "index species" in {
      val query    = queryField(neuronMorphologyId, "subjectSpecies")
      val expected =
        json"""
          {
            "subjectSpecies" : {
              "@id" : "http://purl.obolibrary.org/obo/NCBITaxon_10116",
              "identifier" : "http://purl.obolibrary.org/obo/NCBITaxon_10116",
              "label" : "Rattus norvegicus"
            }
          }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index distribution" in {
      val query    = queryField(neuronMorphologyId, "distribution")
      val expected =
        json"""
          {
          "distribution" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/b6cb3fbd-73d6-47c7-bf23-3a993b326afa",
              "contentSize" : 522260,
              "contentUrl" : "https://bbp.epfl.ch/nexus/v1/files/public/sscx/b6cb3fbd-73d6-47c7-bf23-3a993b326afa",
              "encodingFormat" : "application/swc",
              "label" : "sm080522a1-5_idA.swc"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }
    "index contributor" in {
      // The affiliation property of a contributor is not indexed as expected
      // because of the @embed property being set to @last. If another field
      // already embeds the object with the same @id, it will not embed it
      // for the affiliation.
      pending
      val query    = queryField(neuronMorphologyId, "contributors")
      val expected =
        json"""
        {
          "contributors" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/d3a0dafe-f8ed-4b4d-bd90-93d64baf63a1",
              "idLabel" : "https://bbp.epfl.ch/neurosciencegraph/data/d3a0dafe-f8ed-4b4d-bd90-93d64baf63a1|John Doe",
              "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/d3a0dafe-f8ed-4b4d-bd90-93d64baf63a1",
              "label" : "John Doe",
              "affiliation": {
                "@id": "https://www.grid.ac/institutes/grid.5333.6",
                "label": "École Polytechnique Fédérale de Lausanne"
              }
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index organization" in {
      val query    = queryField(neuronMorphologyId, "organizations")
      val expected =
        json"""
        {
          "organizations" : [
            {
              "@id" : "https://www.grid.ac/institutes/grid.5333.6",
              "idLabel" : "https://www.grid.ac/institutes/grid.5333.6|École Polytechnique Fédérale de Lausanne",
              "identifier" : "https://www.grid.ac/institutes/grid.5333.6",
              "label" : "École Polytechnique Fédérale de Lausanne"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index generation" in {
      val query    = queryField(neuronMorphologyId, "generation")
      val expected =
        json"""
        {
          "generation" : {
            "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/6af3b9db-fa85-4190-93c7-156a715c5aa3",
            "protocol" : [
              {
                "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/2f8814d6-a40f-4377-a675-e164816c5d73",
                "label" : "Reconstruction and Simulation of Neocortical Microcircuitry",
                "propertyID" : "doi",
                "value" : "https://doi.org/10.1016/j.cell.2015.09.029"
              }
            ],
            "startedAt" : "2015-01-21T00:00:00",
            "endedAt" : "2015-05-01T00:00:00"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index derivation" in {
      val query    = queryField(neuronMorphologyId, "derivation")
      val expected =
        json"""
        {
          "derivation" : [
            {
              "@type" : [
                "https://neuroshapes.org/PatchedCell",
                "http://www.w3.org/ns/prov#Entity"
              ],
              "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/22e90788-b089-4014-83ab-206d0d3af71d",
              "label" : "sm080522a1-5_idA"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index mType" in {
      val query    = queryField(traceId, "mType")
      val expected =
        json"""
        {
          "mType" : {
            "@id" : "http://uri.interlex.org/base/ilx_0381373",
            "idLabel" : "http://uri.interlex.org/base/ilx_0381373|L6_IPC",
            "identifier" : "http://uri.interlex.org/base/ilx_0381373",
            "label" : "L6_IPC"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }
    "index eType" in {
      val query    = queryField(traceId, "eType")
      val expected =
        json"""
        {
          "eType" : {
            "@id" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr",
            "idLabel" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr|cADpyr",
            "identifier" : "http://bbp.epfl.ch/neurosciencegraph/ontologies/etypes/cADpyr",
            "label" : "cADpyr"
          }
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index image" in {
      val query    = queryField(traceId, "image")
      val expected =
        json"""
        {
          "image" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/58342dff-8034-4b53-933b-1c034cdc8180",
              "about" : "https://neuroshapes.org/StimulationTrace",
              "identifier" : "https://bbp.epfl.ch/neurosciencegraph/data/58342dff-8034-4b53-933b-1c034cdc8180",
              "repetition" : 1,
              "stimulusType" : "step_2"
            }
          ]
        }
            """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index subject age (exact value)" in {
      val query    = queryField(layerThicknessId, "subjectAge")
      val expected =
        json"""
        {
          "subjectAge" : {
            "label" : "56 days Post-natal",
            "period" : "Post-natal",
            "unit" : "days",
            "value" : 56
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index subject weight (exact value)" in {
      val query    = queryField(layerThicknessId, "subjectWeight")
      val expected =
        json"""
        {
          "subjectWeight" : {
            "label" : "37.1 g",
            "unit" : "g",
            "value" : 37.1
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index subject age (min/max case)" in {
      val query    = queryField(neuronDensityId, "subjectAge")
      val expected =
        json"""
        {
          "subjectAge" : {
            "label" : "9 to 10 weeks Post-natal",
            "period" : "Post-natal",
            "unit" : "weeks",
            "minValue" : 9,
            "maxValue" : 10
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index subject weight (min/max case)" in {
      val query    = queryField(neuronDensityId, "subjectWeight")
      val expected =
        json"""
        {
          "subjectWeight" : {
            "label" : "37.1 to 40 g",
            "unit" : "g",
            "minValue" : 37.1,
            "maxValue" : 40
          }
        }
           """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index neuron density" in {
      val query    = queryField(neuronDensityId, "neuronDensity")
      val expected =
        json"""
        {
          "neuronDensity" : {
            "label" : "35200.0 neurons/mm³ (N = 5)",
            "nValue" : 5,
            "unit" : "neurons/mm³",
            "value" : 35200.0
          }
        }
          """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index layer thickness" in {
      val query    = queryField(layerThicknessId, "layerThickness")
      val expected =
        json"""
        {
          "layerThickness" : {
            "label" : "250 µm (N = 1)",
            "nValue" : 1,
            "unit" : "µm",
            "value" : 250
          }
        }
         """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index bouton density" in {
      val query    = queryField(boutonDensityId, "boutonDensity")
      val expected =
        json"""
        {
          "boutonDensity" : {
            "label" : "0.1212 boutons/μm",
            "unit" : "boutons/μm",
            "value" : 0.1212
          }
        }
        """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index series" in {
      val query    = queryField(boutonDensityId, "series")
      val expected =
        json"""
        {
          "series" : [
            {
              "statistic" : "mean",
              "unit" : "boutons/μm",
              "value" : 0.1212
            }
          ]
        }
       """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index source" in {
      val query    = queryField(neuronMorphologyId, "source")
      val expected =
        json"""
        {
          "source" : [
            {
              "@id" : "https://bbp.epfl.ch/neurosciencegraph/data/scholarlyarticles/1",
              "@type" : [
                "http://schema.org/ScholarlyArticle",
                "http://www.w3.org/ns/prov#Entity"
              ],
              "identifier" : [
                {
                  "propertyID" : "doi",
                  "value" : "10.1093/cercor/bht274"
                },
                {
                  "propertyID" : "PMID",
                  "value" : 24108800
                }
              ],
              "title" : "Cell type-specific effects of adenosine on cortical neurons."
            }
          ]
        }
      """

      assertOneSource(query) { json =>
        json should equalIgnoreArrayOrder(expected)
      }
    }

    "index metadata" in { pending }
    "index detailed circuit" in { pending }
    "index simulation campaign config" in { pending }
    "index sType" in {
      // there are no resources with this field yet
      pending
    }

  }

  /**
    * Defines an ES query that searches for the document with the provided id and limits the resulting source to just
    * the requested field
    */
  private def queryField(id: String, field: String) =
    jsonContentOf("/kg/search/id-query.json", "id" -> id, "field" -> field)

  /** Post a resource across all defined projects in the suite */
  private def postResource(resourcePath: String): Task[List[Assertion]] = {
    val json = jsonContentOf(resourcePath)
    projects.parTraverse { project =>
      for {
        _ <- deltaClient.post[Json](s"/resources/$project/_/", json, Rick) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
      } yield succeed
    }
  }

  /**
    * Queries ES using the provided query. Asserts that there is only on result in _source. Runs the provided assertion
    * on the _source.
    */
  private def assertOneSource(query: Json)(assertion: Json => Assertion): Task[Assertion] =
    eventually {
      deltaClient.post[Json]("/search/query", query, Rick) { (body, response) =>
        response.status shouldEqual StatusCodes.OK
        val sources = Json.fromValues(body.findAllByKey("_source"))
        val source  = sources.hcursor.downArray.as[Json]
        val actual  = source.getOrElse(Json.Null)

        sources.asArray.value.size shouldBe 1
        assertion(actual)
      }
    }

}
