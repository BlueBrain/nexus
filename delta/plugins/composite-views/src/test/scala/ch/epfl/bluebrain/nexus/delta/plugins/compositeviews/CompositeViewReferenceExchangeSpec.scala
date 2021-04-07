package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ProjectSetup}
import monix.execution.Scheduler
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class CompositeViewReferenceExchangeSpec
    extends AbstractDBSpec
    with Inspectors
    with CompositeViewsSetup
    with OptionValues {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller.unsafe(subject)
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs)         = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted
  private val views: CompositeViews = initViews(orgs, projs).accepted

  "A CompositeViewReferenceExchange" should {
    val id         = iri"http://localhost/${genString()}"
    val viewSource = jsonContentOf("composite-view-source.json")
    val tag        = TagLabel.unsafe("tag")
    val resRev1    = views.create(id, project.ref, viewSource).accepted
    val resRev2    = views.tag(id, project.ref, tag, 1L, 1L).accepted

    val exchange = new CompositeViewReferenceExchange(views)

    "return a view by id" in {
      val value = exchange.toResource(project.ref, Latest(id)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev2
    }

    "return a view by tag" in {
      val value = exchange.toResource(project.ref, Tag(id, tag)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev1
    }

    "return a view by rev" in {
      val value = exchange.toResource(project.ref, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev1
    }

    "return a view by schema and id" in {
      val value = exchange.toResource(project.ref, model.schema, Latest(id)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev2
    }

    "return a view by schema and tag" in {
      val value = exchange.toResource(project.ref, model.schema, Tag(id, tag)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev1
    }

    "return a view by schema and rev" in {
      val value = exchange.toResource(project.ref, model.schema, Revision(id, 1L)).accepted.value
      value.toSource shouldEqual viewSource.removeAllKeys("token")
      value.toResource shouldEqual resRev1
    }

    "return None for incorrect schema" in {
      forAll(List(Latest(id), Tag(id, tag), Revision(id, 1L))) { ref =>
        exchange.toResource(project.ref, Latest(iri"http://localhost/${genString()}"), ref).accepted shouldEqual None
      }
    }

    "return None for incorrect id" in {
      exchange.toResource(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.toResource(project.ref, model.schema, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.toResource(project.ref, model.schema, Tag(id, label)).accepted shouldEqual None
    }
  }
}
