package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectContext, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import monix.bio.IO

class FetchContextDummy private (
    expected: Map[ProjectRef, ProjectContext],
    rejectOnCreate: Set[ProjectRef],
    rejectOnModify: Set[ProjectRef]
) extends FetchContext[ContextRejection] {

  override def defaultApiMappings: ApiMappings = ApiMappings.empty

  override def onRead(ref: ProjectRef): IO[ContextRejection, ProjectContext] =
    IO.fromEither(expected.get(ref).toRight(ContextRejection(ProjectNotFound(ref).asInstanceOf[ProjectRejection])))

  override def onCreate(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ContextRejection, ProjectContext] =
    IO.raiseWhen(rejectOnCreate.contains(ref))(
      ContextRejection(ProjectIsDeprecated(ref).asInstanceOf[ProjectRejection])
    ) >> onRead(ref)

  override def onModify(ref: ProjectRef)(implicit subject: Identity.Subject): IO[ContextRejection, ProjectContext] =
    IO.raiseWhen(rejectOnModify.contains(ref))(
      ContextRejection(ProjectIsDeprecated(ref).asInstanceOf[ProjectRejection])
    ) >> onRead(ref)
}

object FetchContextDummy {

  val empty = new FetchContextDummy(Map.empty, Set.empty, Set.empty)

  def apply(
      expected: Map[ProjectRef, ProjectContext],
      rejectOnCreateOrModify: Set[ProjectRef]
  ): FetchContext[ContextRejection] =
    new FetchContextDummy(expected, rejectOnCreateOrModify, rejectOnCreateOrModify)

  def apply(expected: Map[ProjectRef, ProjectContext]): FetchContext[ContextRejection] =
    new FetchContextDummy(expected, Set.empty, Set.empty)

  def apply(expected: List[Project]): FetchContext[ContextRejection] =
    new FetchContextDummy(expected.map { p => p.ref -> p.context }.toMap, Set.empty, Set.empty)

  def apply[R](
      expected: Map[ProjectRef, ProjectContext],
      rejectOnCreate: Set[ProjectRef],
      rejectOnModify: Set[ProjectRef],
      f: ContextRejection => R
  ): FetchContext[R]                                                 =
    new FetchContextDummy(expected, rejectOnCreate, rejectOnModify).mapRejection(f)

  def apply[R](
      expected: Map[ProjectRef, ProjectContext],
      rejectOnCreateOrModify: Set[ProjectRef],
      f: ContextRejection => R
  ): FetchContext[R] =
    apply(expected, rejectOnCreateOrModify, rejectOnCreateOrModify, f)

  def apply[R](expected: Map[ProjectRef, ProjectContext], f: ContextRejection => R): FetchContext[R] =
    apply(expected, Set.empty, f)

  def apply[R](projects: List[Project], f: ContextRejection => R): FetchContext[R] =
    apply(projects.map { p => p.ref -> p.context }.toMap, Set.empty, f)

}
