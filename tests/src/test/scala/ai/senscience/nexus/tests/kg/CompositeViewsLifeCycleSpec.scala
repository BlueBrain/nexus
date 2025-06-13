package ai.senscience.nexus.tests.kg

import ai.senscience.nexus.tests.BaseIntegrationSpec
import ai.senscience.nexus.tests.Identity.compositeviews.Jerry
import ai.senscience.nexus.tests.iam.types.Permission.{Events, Organizations, Views}
import ai.senscience.nexus.tests.kg.CompositeViewsLifeCycleSpec.Spaces.ProjectionSpace
import ai.senscience.nexus.tests.kg.CompositeViewsLifeCycleSpec.{Spaces, query}
import cats.data.NonEmptyMap
import cats.effect.IO
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import org.scalactic.source.Position

final class CompositeViewsLifeCycleSpec extends BaseIntegrationSpec {

  private val orgId   = genId()
  private val projId  = genId()
  private val proj2Id = genId()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val setup = for {
      _ <- aclDsl.cleanAcls(Jerry)
      _ <- aclDsl.addPermissions("/", Jerry, Set(Organizations.Create, Views.Query, Events.Read))
      _ <- adminDsl.createOrganization(orgId, orgId, Jerry)
      _ <- adminDsl.createProjectWithName(orgId, projId, name = projId, Jerry)
      _ <- adminDsl.createProjectWithName(orgId, proj2Id, name = proj2Id, Jerry)
    } yield ()
    setup.accepted
  }

  private val viewEndpoint = s"/views/$orgId/$projId/composite"

  private def createView(query: String, includeCrossProject: Boolean, includeSparqlProjection: Boolean) = {
    val includeCrossProjectOpt     = Option.when(includeCrossProject)("cross_project" -> includeCrossProject.toString)
    val includeSparqlProjectionOpt = Option.when(includeSparqlProjection)("sparql" -> includeSparqlProjection.toString)
    val values                     = List(
      "org"   -> orgId,
      "proj"  -> proj2Id,
      "query" -> query
    ) ++ includeCrossProjectOpt ++ includeSparqlProjectionOpt
    IO(
      jsonContentOf(
        "kg/views/composite/composite-view-lifecycle.json",
        replacements(
          Jerry,
          values*
        )*
      )
    )
  }

  private def fetchSpaces = deltaClient.getJson[Spaces](s"$viewEndpoint/description", Jerry)

  private def includeAllSpaces(spaces: Spaces)(implicit pos: Position) = {
    eventually { sparqlDsl.includes(spaces.commonSpace) }
    eventually { spaces.sparqlProjection.foreach { s => sparqlDsl.includes(s.value) } }
    eventually { spaces.elasticSearchProjection.foreach { e => elasticsearchDsl.includes(e.value) } }
  }

  private def excludeAllSpaces(spaces: Spaces)(implicit pos: Position) = {
    eventually { sparqlDsl.excludes(spaces.commonSpace) }
    eventually { spaces.sparqlProjection.foreach { s => sparqlDsl.excludes(s.value) } }
    eventually { spaces.elasticSearchProjection.foreach { e => elasticsearchDsl.excludes(e.value) } }
  }

  "Lifecycle of a composite view" should {
    "handle correctly the expected spaces" in {
      for {
        // Creating the view
        version1 <- createView(query, includeCrossProject = true, includeSparqlProjection = true)
        _        <- deltaClient.put[Json](s"$viewEndpoint", version1, Jerry) { expectCreated }
        spaces1  <- fetchSpaces
        _         = includeAllSpaces(spaces1)
        // Updating the view, removing the cross-project source
        // All spaces must have been recreated
        version2 <- createView(query, includeCrossProject = false, includeSparqlProjection = true)
        _        <- deltaClient.put[Json](s"$viewEndpoint?rev=1", version2, Jerry) { expectOk }
        spaces2  <- fetchSpaces
        _         = excludeAllSpaces(spaces1)
        _         = includeAllSpaces(spaces2)
        // Updating the view, removing the sparql projection
        // Only the sparql projection must have been removed
        version3 <- createView(query, includeCrossProject = false, includeSparqlProjection = false)
        _        <- deltaClient.put[Json](s"$viewEndpoint?rev=2", version3, Jerry) { expectOk }
        spaces3  <- fetchSpaces
        _         = spaces3.commonSpace shouldEqual spaces2.commonSpace
        _         = spaces3.projectionSpaces.length shouldEqual 1
        _         = spaces3.elasticSearchProjection shouldEqual spaces2.elasticSearchProjection
        _         = includeAllSpaces(spaces3)
        _         = sparqlDsl.excludes(spaces2.sparqlProjection.value.value)
        // Updating the view,updating the query for the remaining projection
        version4 <- createView(query + " ", includeCrossProject = false, includeSparqlProjection = false)
        _        <- deltaClient.put[Json](s"$viewEndpoint?rev=3", version4, Jerry) { expectOk }
        spaces4  <- fetchSpaces
        _         = spaces4.commonSpace shouldEqual spaces3.commonSpace
        _         = spaces4.projectionSpaces.length shouldEqual 1
        _         = includeAllSpaces(spaces4)
        _         = elasticsearchDsl.excludes(spaces3.elasticSearchProjection.value.value)
        // Deprecate the view should delete all spaces
        _        <- deltaClient.delete[Json](s"$viewEndpoint?rev=4", Jerry) { expectOk }
        _        <- deltaClient.get[Json](s"$viewEndpoint/description", Jerry) { expectBadRequest }
        _         = excludeAllSpaces(spaces4)
      } yield succeed
    }
  }

}

object CompositeViewsLifeCycleSpec {

  final case class Spaces(commonSpace: String, projectionSpaces: NonEmptyMap[String, ProjectionSpace]) {
    def elasticSearchProjection: Option[ProjectionSpace] = projectionSpaces(
      "https://localhost/projections/es-projection"
    )
    def sparqlProjection: Option[ProjectionSpace]        = projectionSpaces("https://localhost/projections/sparql-projection")

  }

  object Spaces {
    final case class ProjectionSpace(value: String)

    implicit val projectionSpaceDecoder: Decoder[ProjectionSpace] = deriveDecoder[ProjectionSpace]
    implicit val spacesDecoder: Decoder[Spaces]                   = deriveDecoder[Spaces]
  }

  private val query =
    """
      |prefix schema: <http://schema.org/>
      |
      |CONSTRUCT {
      |  ?alias   a              ?type ;
      |           schema:name    ?name .
      |} WHERE {
      |    VALUES ?id { {resource_id} }
      |    BIND(IRI(concat(str(?id), '/alias')) AS ?alias)
      |
      |    ?id  a  ?type  .
      |    OPTIONAL { ?id  schema:name  ?name . } .
      |}
      |""".stripMargin.replaceAll("\\n", " ")

}
