package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverValue.InProjectValue
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Priority, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite

class ResolverScopeInitializationSuite extends CatsEffectSuite {

  private val defaults = Defaults("resolverName", "resolverDescription")

  private val project = ProjectGen.project("org", "project")

  private val usersRealm: Label = Label.unsafe("users")
  private val bob: Subject      = User("bob", usersRealm)

  test("Succeeds") {
    for {
      ref      <- Ref.of[IO, Option[ResolverValue]](None)
      scopeInit = new ResolverScopeInitialization(
                    (_, resolver) => ref.set(Some(resolver)),
                    defaults
                  )
      _        <- scopeInit.onProjectCreation(project, bob)
      expected  = InProjectValue(Some(defaults.name), Some(defaults.description), Priority.unsafe(1))
      _        <- ref.get.assertEquals(Some(expected))
    } yield ()
  }

  test("Recovers if the resolver already exists") {
    val scopeInit = new ResolverScopeInitialization(
      (project, _) => IO.raiseError(ResourceAlreadyExists(nxv.defaultResolver, project)),
      defaults
    )
    scopeInit.onProjectCreation(project, bob).assert
  }

  test("Raises a failure otherwise") {
    val scopeInit = new ResolverScopeInitialization(
      (_, _) => IO.raiseError(new IllegalStateException("Something got wrong !")),
      defaults
    )
    scopeInit.onProjectCreation(project, bob).intercept[ScopeInitializationFailed]
  }
}
