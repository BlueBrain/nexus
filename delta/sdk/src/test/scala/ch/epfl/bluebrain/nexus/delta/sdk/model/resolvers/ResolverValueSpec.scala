package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverValue.{CrossProjectValue, InProjectValue}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResolverValueSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with IOValues
    with Inspectors
    with TestHelpers {

  implicit val res: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.resolvers -> jsonContentOf("/contexts/resolvers.json"))

  val realm                                 = Label.unsafe("myrealm")

  "InProject" should {
    val json     = jsonContentOf("/resolvers/expanded/in-project-resolver.json")
    val expanded = ExpandedJsonLd(json).accepted

    "be successfully decoded" in {
      expanded.to[ResolverValue].rightValue shouldEqual InProjectValue(
        Priority.unsafe(42)
      )
    }

    "generate the correct source from value" in {
      val inProject = InProjectValue(Priority.unsafe(42))
      ResolverValue.generateSource(nxv + "generated", inProject) shouldEqual
        jsonContentOf("resolvers/in-project-from-value.json")
    }
  }

  "CrossProject" should {
    "be successfully decoded when using provided entities resolution" in {
      forAll(
        List(
          jsonContentOf("/resolvers/expanded/cross-project-resolver-identities.json"),
          jsonContentOf("/resolvers/expanded/cross-project-resolver-identities-no-type.json")
        )
      ) { json =>
        val expanded = ExpandedJsonLd(json).accepted
        expanded.to[ResolverValue].rightValue shouldEqual CrossProjectValue(
          Priority.unsafe(42),
          Set(nxv.Schema),
          NonEmptyList.of(ProjectRef.unsafe("org", "proj"), ProjectRef.unsafe("org", "proj2")),
          ProvidedIdentities(Set(User("Bob", realm), Group("mygroup", realm), Authenticated(realm)))
        )
      }
    }

    "be successfully decoded when using current caller resolution" in {
      val json     = jsonContentOf("/resolvers/expanded/cross-project-resolver-use-caller.json")
      val expanded = ExpandedJsonLd(json).accepted
      expanded.to[ResolverValue].rightValue shouldEqual CrossProjectValue(
        Priority.unsafe(42),
        Set(nxv.Schema),
        NonEmptyList.of(ProjectRef.unsafe("org", "proj"), ProjectRef.unsafe("org", "proj2")),
        UseCurrentCaller
      )
    }

    "result in an error when both resolutions are defined" in {
      val json     = jsonContentOf("/resolvers/expanded/cross-project-resolver-both-error.json")
      val expanded = ExpandedJsonLd(json).accepted
      expanded.to[ResolverValue].leftValue shouldEqual ParsingFailure(
        "Only 'useCurrentCaller' or 'identities' should be defined"
      )
    }

    "generate the correct source from resolver using provided entities resolution" in {
      val crossProjectProject = CrossProjectValue(
        Priority.unsafe(42),
        Set(nxv.Schema),
        NonEmptyList.of(
          ProjectRef.unsafe("org", "project1"),
          ProjectRef.unsafe("org", "project2")
        ),
        ProvidedIdentities(Set(User("alice", realm)))
      )
      ResolverValue.generateSource(nxv + "generated", crossProjectProject) shouldEqual
        jsonContentOf("resolvers/cross-project-provided-entities-from-value.json")
    }

    "generate the correct source from resolver using current caller resolution" in {
      val crossProjectProject = CrossProjectValue(
        Priority.unsafe(42),
        Set(nxv.Schema),
        NonEmptyList.of(
          ProjectRef.unsafe("org", "project1"),
          ProjectRef.unsafe("org", "project2")
        ),
        UseCurrentCaller
      )
      ResolverValue.generateSource(nxv + "generated", crossProjectProject) shouldEqual
        jsonContentOf("resolvers/cross-project-current-caller-from-value.json")
    }
  }

}
