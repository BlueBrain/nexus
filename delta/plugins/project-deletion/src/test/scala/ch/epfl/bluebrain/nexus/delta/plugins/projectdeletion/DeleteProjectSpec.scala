package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectSetup
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID

class DeleteProjectSpec extends AnyWordSpecLike with Matchers with IOValues {

  private val uuid                   = UUID.randomUUID()
  implicit private val uuidF: UUIDF  = UUIDF.fixed(uuid)
  implicit private val sc: Scheduler = Scheduler.global

  implicit val baseUri: BaseUri = BaseUri.withoutPrefix("http://localhost")

  private val saRealm: Label              = Label.unsafe("service-accounts")
  implicit private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  implicit private val subject: Subject   = sa.subject

  implicit private val prf: ProjectReferenceFinder = (_: ProjectRef) => UIO.pure(ProjectReferenceMap.empty)

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (_, projs) = ProjectSetup.init(orgsToCreate = org :: Nil, projectsToCreate = project :: Nil).accepted

  private val deleteProject: DeleteProject = DeleteProject(projs)

  "A DeleteProject" should {

    "ignore recover gracefully in case of rejections" in {
      val pr = ProjectGen.resourceFor(project, rev = 2L)
      deleteProject(pr).accepted
      projs.fetch(project.ref).map(_.value.markedForDeletion).accepted shouldEqual false
    }

    "mark a project for deletion" in {
      val pr = ProjectGen.resourceFor(project)
      deleteProject(pr).accepted
      projs.fetch(project.ref).map(_.value.markedForDeletion).accepted shouldEqual true
    }
  }

}
