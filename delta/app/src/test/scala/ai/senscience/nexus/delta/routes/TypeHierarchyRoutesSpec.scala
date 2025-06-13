package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.TypeHierarchyResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.typehierarchy
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.TypeHierarchy
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchyRejection.TypeHierarchyDoesNotExist
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.{TypeHierarchy as TypeHierarchyModel, TypeHierarchyState}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import io.circe.syntax.EncoderOps
import org.scalatest.{Assertion, BeforeAndAfterEach}

import java.time.Instant

class TypeHierarchyRoutesSpec extends BaseRouteSpec with BeforeAndAfterEach {

  private val writer     = User("writer", Label.unsafe(genString()))
  private val identities = IdentitiesDummy.fromUsers(writer)
  private val aclCheck   = AclSimpleCheck.unsafe(
    (writer, AclAddress.Root, Set(typehierarchy.write))
  )

  private val typeHierarchyRef = Ref.unsafe[IO, Option[TypeHierarchyResource]](None)

  private val typeHierarchy = new TypeHierarchy {
    override def create(
        mapping: TypeHierarchyMapping
    )(implicit subject: Identity.Subject): IO[TypeHierarchyResource] = {
      typeHierarchyRef.set(Some(typeHierarchyResource(rev = 1))) >>
        IO.pure(typeHierarchyResource(rev = 1))
    }

    override def update(mapping: TypeHierarchyMapping, rev: Int)(implicit
        subject: Identity.Subject
    ): IO[TypeHierarchyResource] =
      typeHierarchyRef
        .getAndSet(Some(typeHierarchyResource(rev = rev + 1)))
        .flatMap(IO.fromOption(_)(TypeHierarchyDoesNotExist))

    override def fetch: IO[TypeHierarchyResource] =
      typeHierarchyRef.get.flatMap(IO.fromOption(_)(TypeHierarchyDoesNotExist))

    override def fetch(rev: Int): IO[TypeHierarchyResource] =
      typeHierarchyRef.get.flatMap(IO.fromOption(_)(TypeHierarchyDoesNotExist))
  }

  private val routes = Route.seal(
    new TypeHierarchyRoutes(
      typeHierarchy,
      identities,
      aclCheck
    ).routes
  )

  private val mapping     = Map(
    iri"https://schema.org/Movie" -> Set(iri"https://schema.org/CreativeWork", iri"https://schema.org/Thing")
  )
  private val jsonMapping = TypeHierarchyModel(mapping).asJson

  override def beforeEach(): Unit = {
    super.beforeEach()
    typeHierarchyRef.set(None).accepted
  }

  "The TypeHierarchyRoutes" should {
    "fail to create the type hierarchy without permissions" in {
      Post("/v1/type-hierarchy", jsonMapping.toEntity) ~> routes ~> check {
        response.shouldBeForbidden
        typeHierarchyRef.get.accepted shouldEqual None
      }
    }

    "succeed to create the type hierarchy with write permissions" in {
      Post("/v1/type-hierarchy", jsonMapping.toEntity) ~> as(writer) ~> routes ~> check {
        status shouldEqual StatusCodes.Created
        typeHierarchyRef.get.accepted shouldEqual Some(typeHierarchyResource(rev = 1))
      }
    }

    "return the type hierarchy anonymously" in {
      givenATypeHierarchyExists {
        Get("/v1/type-hierarchy") ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          typeHierarchyRef.get.accepted shouldEqual Some(typeHierarchyResource(rev = 1))
        }
      }
    }

    "fail to update the type hierarchy without permissions" in {
      givenATypeHierarchyExists {
        Put("/v1/type-hierarchy?rev=1", jsonMapping.toEntity) ~> routes ~> check {
          response.shouldBeForbidden
          typeHierarchyRef.get.accepted shouldEqual Some(typeHierarchyResource(rev = 1))
        }
      }
    }

    "succeed to update the type hierarchy with write permissions" in {
      givenATypeHierarchyExists {
        Put("/v1/type-hierarchy?rev=1", jsonMapping.toEntity) ~> as(writer) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          typeHierarchyRef.get.accepted shouldEqual Some(typeHierarchyResource(rev = 2))
        }
      }
    }

  }

  def givenATypeHierarchyExists(test: => Assertion): Assertion =
    Post("/v1/type-hierarchy", jsonMapping.toEntity) ~> as(writer) ~> routes ~> check {
      status shouldEqual StatusCodes.Created
      test
    }

  private def typeHierarchyResource(rev: Int) =
    TypeHierarchyState(
      Map(
        iri"https://schema.org/Movie" -> Set(iri"https://schema.org/CreativeWork", iri"https://schema.org/Thing")
      ),
      rev = rev,
      deprecated = false,
      createdAt = Instant.EPOCH,
      createdBy = writer,
      updatedAt = Instant.EPOCH,
      updatedBy = writer
    ).toResource

}
