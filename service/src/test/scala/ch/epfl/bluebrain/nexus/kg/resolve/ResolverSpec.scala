package ch.epfl.bluebrain.nexus.kg.resolve

import java.time.{Clock, Instant, ZoneId}
import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.{Id => CId}
import ch.epfl.bluebrain.nexus.admin.projects.Project
import ch.epfl.bluebrain.nexus.commons.test.{CirceEq, EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Group, User}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resolve.Resolver._
import ch.epfl.bluebrain.nexus.kg.resolve.ResolverEncoder._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.Json
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues, TryValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResolverSpec
    extends TestKit(ActorSystem("ResolverSpec"))
    with AnyWordSpecLike
    with Matchers
    with Resources
    with EitherValues
    with OptionValues
    with IdiomaticMockito
    with BeforeAndAfter
    with TestHelper
    with TryValues
    with Inspectors
    with CirceEq {

  implicit private val clock            = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())
  implicit private val appConfig        = Settings(system).serviceConfig
  implicit private val http: HttpConfig = appConfig.http

  implicit private val projectCache = mock[ProjectCache[CId]]

  before {
    Mockito.reset(projectCache)
  }

  "A Resolver" when {
    val inProject        = jsonContentOf("/resolve/in-project.json").appendContextOf(resolverCtx)
    val crossProject     = jsonContentOf("/resolve/cross-project.json").appendContextOf(resolverCtx)
    val crossProjectAnon = jsonContentOf("/resolve/cross-project3.json").appendContextOf(resolverCtx)
    val crossProjectRefs = jsonContentOf("/resolve/cross-project-refs.json").appendContextOf(resolverCtx)
    val iri              = Iri.absolute("http://example.com/id").rightValue
    val projectRef1      = ProjectRef(genUUID)
    val label1           = ProjectLabel("account1", "project1")
    val id               = Id(projectRef1, iri)
    val projectRef2      = ProjectRef(genUUID)
    val label2           = ProjectLabel("account1", "project2")
    val identities       = List[Identity](Group("bbp-ou-neuroinformatics", "ldap2"), User("dmontero", "ldap"))

    // format: off
    val project1 = Project(genIri, label1.value, label1.organization, None, genIri, genIri, Map.empty, projectRef1.id, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
    val project2 = Project(genIri, label2.value, label2.organization, None, genIri, genIri, Map.empty, projectRef2.id, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
    // format: on

    "constructing" should {

      "return an InProjectResolver" in {
        val resource = simpleV(id, inProject, types = Set(nxv.Resolver.value, nxv.InProject.value, nxv.Resource.value))
        Resolver(resource).rightValue shouldEqual
          InProjectResolver(projectRef1, iri, resource.rev, resource.deprecated, 10)
      }

      "return a CrossProjectResolver" in {
        val resource = simpleV(id, crossProject, types = Set(nxv.Resolver.value, nxv.CrossProject.value))
        val projects = List(label1, label2)
        val resolver = Resolver(resource).rightValue.asInstanceOf[CrossProjectResolver]
        resolver.priority shouldEqual 50
        resolver.identities should contain theSameElementsAs identities
        resolver.resourceTypes shouldEqual Set(nxv.Schema.value)
        resolver.projects shouldEqual projects
        resolver.ref shouldEqual projectRef1
        resolver.id shouldEqual iri
        resolver.rev shouldEqual resource.rev
        resolver.deprecated shouldEqual resource.deprecated
      }
      "return a CrossProjectResolver with anonymous identity" in {
        val resource = simpleV(id, crossProjectAnon, types = Set(nxv.Resolver.value, nxv.CrossProject.value))
        Resolver(resource).rightValue.asInstanceOf[CrossProjectResolver] shouldEqual
          CrossProjectResolver(
            Set(nxv.Schema.value),
            List(ProjectLabel("account1", "project1"), ProjectLabel("account1", "project2")),
            Set(Anonymous),
            projectRef1,
            iri,
            resource.rev,
            resource.deprecated,
            50
          )

      }

      "return a CrossProjectResolver that does not have resourceTypes" in {
        val resource = simpleV(id, crossProjectRefs, types = Set(nxv.Resolver.value, nxv.CrossProject.value))
        val resolver = Resolver(resource).rightValue.asInstanceOf[CrossProjectResolver]
        resolver.priority shouldEqual 50
        resolver.identities should contain theSameElementsAs identities
        resolver.resourceTypes shouldEqual Set.empty
        resolver.projects shouldEqual List(ProjectRef(UUID.fromString("ee9bb6e8-2bee-45f8-8a57-82e05fff0169")))
        resolver.ref shouldEqual projectRef1
        resolver.id shouldEqual iri
        resolver.rev shouldEqual resource.rev
        resolver.deprecated shouldEqual resource.deprecated
      }

      "fail when the types don't match" in {
        val resource = simpleV(id, inProject, types = Set(nxv.Resource.value))
        Resolver(resource).toOption shouldEqual None
      }

      "fail when payload on identity is wrong" in {
        val invalid = List.range(1, 3).map(i => jsonContentOf(s"/resolve/cross-project-wrong-$i.json"))
        forAll(invalid) { invalidResolver =>
          val resource =
            simpleV(
              id,
              invalidResolver.appendContextOf(resolverCtx),
              types = Set(nxv.Resolver.value, nxv.CrossProject.value)
            )
          Resolver(resource).toOption shouldEqual None
        }
      }
    }

    "converting into json " should {

      "return the json representation" in {

        val resolver: Resolver = CrossProjectResolver(
          Set(nxv.Schema.value),
          List(ProjectLabel("account1", "project1"), ProjectLabel("account1", "project2")),
          Set(Anonymous),
          projectRef1,
          iri,
          1L,
          false,
          50
        )

        val metadata = Json.obj(
          "_rev"        -> Json.fromLong(1L),
          "_deprecated" -> Json.fromBoolean(false),
          "identities"  -> Json.arr(
            Json.obj(
              "@id"   -> Json.fromString("http://127.0.0.1:8080/v1/anonymous"),
              "@type" -> Json.fromString("Anonymous")
            )
          )
        )
        val json     =
          resolver.asGraph.toJson(resolverCtx.appendContextOf(resourceCtx)).rightValue.removeNestedKeys("@context")
        json should equalIgnoreArrayOrder(crossProjectAnon.removeNestedKeys("@context") deepMerge metadata)
      }
    }

    "converting" should {

      "generate a CrossProjectResolver" in {
        projectCache.get(label1) shouldReturn Some(project1)
        projectCache.get(label2) shouldReturn Some(project2)
        val resource = simpleV(id, crossProject, types = Set(nxv.Resolver.value, nxv.CrossProject.value))
        val exposed  = Resolver(resource).rightValue.asInstanceOf[CrossProjectResolver]
        val stored   = exposed.referenced[CId].value.rightValue.asInstanceOf[CrossProjectResolver]
        stored.priority shouldEqual 50
        stored.identities should contain theSameElementsAs identities
        stored.resourceTypes shouldEqual Set(nxv.Schema.value)
        stored.projects shouldEqual List(projectRef1, projectRef2)
        stored.ref shouldEqual projectRef1
        stored.id shouldEqual iri
        stored.rev shouldEqual resource.rev
        stored.deprecated shouldEqual resource.deprecated
      }

      "generate a CrossProjectLabelResolver" in {
        projectCache.getLabel(projectRef1) shouldReturn Some(project1.projectLabel)
        projectCache.getLabel(projectRef2) shouldReturn Some(project2.projectLabel)
        projectCache.get(label1) shouldReturn Some(project1)
        projectCache.get(label2) shouldReturn Some(project2)

        val resource = simpleV(id, crossProject, types = Set(nxv.Resolver.value, nxv.CrossProject.value))
        val exposed  = Resolver(resource).rightValue.asInstanceOf[CrossProjectResolver]
        val stored   = exposed.referenced[CId].value.rightValue.asInstanceOf[CrossProjectResolver]
        stored.labeled[CId].value.rightValue.asInstanceOf[CrossProjectResolver] shouldEqual exposed
      }
    }
  }

}
