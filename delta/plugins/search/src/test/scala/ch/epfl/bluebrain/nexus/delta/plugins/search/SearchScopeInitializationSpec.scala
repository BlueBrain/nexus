package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{CompositeViews, CompositeViewsSetup}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ProjectSetup}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.JsonObject
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class SearchScopeInitializationSpec
    extends AbstractDBSpec
    with CompositeViewsSetup
    with AnyWordSpecLike
    with Matchers
    with IOValues {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val saRealm: Label              = Label.unsafe("service-accounts")
  private val usersRealm: Label           = Label.unsafe("users")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val bob: Subject       = User("bob", usersRealm)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs)         = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted
  private val views: CompositeViews = initViews(orgs, projs).accepted

  private val indexingConfig =
    IndexingConfig(Set.empty, JsonObject.empty, None, SparqlConstructQuery.unsafe(""), ContextObject(JsonObject.empty))

  val scopeInit = new SearchScopeInitialization(views, indexingConfig, sa)

  "An SearchScopeInitialization" should {

    "create a composite view on a new project" in {
      views.fetch(defaultViewId, project.ref).rejectedWith[ViewNotFound]
      scopeInit.onProjectCreation(project, bob).accepted
      val view = views.fetch(defaultViewId, project.ref).accepted
      view.rev shouldEqual 1L
      view.createdBy shouldEqual sa.caller.subject
    }

    "not create a composite view if one exists" in {
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
      scopeInit.onProjectCreation(project, bob).accepted
      views.fetch(defaultViewId, project.ref).accepted.rev shouldEqual 1L
    }

  }

}
