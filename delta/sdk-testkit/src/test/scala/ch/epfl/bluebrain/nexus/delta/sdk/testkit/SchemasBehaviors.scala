package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{NoOffset, Sequence}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent.{SchemaCreated, SchemaDeprecated, SchemaTagAdded, SchemaUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{SchemaImports, Schemas}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

trait SchemasBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CancelAfterFailure
    with CirceLiteral =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller   = Caller(subject, Set(subject))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val shaclResolvedCtx                      = jsonContentOf("contexts/shacl.json")
  implicit def res: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  val schemaImports: SchemaImports = new SchemaImports(
    (_, _, _) => IO.raiseError(ResourceResolutionReport()),
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => IO.raiseError(ResourceResolutionReport())
  )

  val org                      = Label.unsafe("myorg")
  val orgDeprecated            = Label.unsafe("org-deprecated")
  val am                       = ApiMappings(Map("nxv" -> nxv.base))
  val projBase                 = nxv.base
  val project                  = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  val projectDeprecated        = ProjectGen.project("myorg", "myproject2")
  val projectWithDeprecatedOrg = ProjectGen.project("org-deprecated", "other-proj")

  val projectRef = project.ref

  val mySchema      = nxv + "myschema"  // Create with id present in payload
  val mySchema2     = nxv + "myschema2" // Create with id present in payload and passed
  val mySchema3     = nxv + "myschema3" // Create with id passed
  val source        = jsonContentOf("resources/schema.json")
  val sourceNoId    = source.removeKeys(keywords.id)
  val schema        = SchemaGen.schema(mySchema, project.ref, source)
  val current       = SchemaGen.currentState(schema, subject = subject)
  val sourceUpdated = sourceNoId.replace("datatype" -> "xsd:integer", "xsd:double")
  val schemaUpdated = SchemaGen.schema(mySchema, project.ref, sourceUpdated)

  lazy val projectSetup: UIO[(OrganizationsDummy, ProjectsDummy)] = ProjectSetup.init(
    orgsToCreate = org :: orgDeprecated :: Nil,
    organizationsToDeprecate = orgDeprecated :: Nil,
    projectsToCreate = project :: projectDeprecated :: projectWithDeprecatedOrg :: Nil,
    projectsToDeprecate = projectDeprecated.ref :: Nil
  )

  def create: UIO[Schemas]

  lazy val schemas: Schemas = create.accepted

  "The Schemas operations bundle" when {

    val tag = TagLabel.unsafe("tag")

    "creating a schema" should {

      "succeed with the id present on the payload" in {
        schemas.create(projectRef, source).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "succeed with the id present on the payload and passed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema2"}"""
        val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId)
        schemas.create(StringSegment("myschema2"), projectRef, sourceWithId).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "succeed with the passed id" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema3"}"""
        val schema       = SchemaGen.schema(mySchema3, project.ref, sourceWithId).copy(source = sourceNoId)
        schemas.create(IriSegment(mySchema3), projectRef, sourceNoId).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, am = am, base = projBase)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        schemas.create(IriSegment(otherId), projectRef, source).rejected shouldEqual
          UnexpectedSchemaId(id = otherId, payloadId = mySchema)
      }

      "reject if it already exists" in {
        schemas.create(IriSegment(mySchema), projectRef, source).rejected shouldEqual SchemaAlreadyExists(mySchema)
      }

      "reject if it does not validate against the SHACL schema" in {
        val otherId     = nxv + "other"
        val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
        schemas.create(IriSegment(otherId), projectRef, wrongSource).rejectedWith[InvalidSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        schemas.create(projectRef, sourceNoId).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        schemas.create(projectDeprecated.ref, sourceNoId).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

      "reject if organization is deprecated" in {
        schemas.create(projectWithDeprecatedOrg.ref, sourceNoId).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "updating a schema" should {

      "succeed" in {
        schemas.update(IriSegment(mySchema), projectRef, 1L, sourceUpdated).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, rev = 2L, subject = subject, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.update(IriSegment(nxv + "other"), projectRef, 1L, json"""{"a": "b"}""").rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.update(IriSegment(mySchema), projectRef, 3L, json"""{"a": "b"}""").rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        schemas.deprecate(IriSegment(mySchema3), projectRef, 1L).accepted
        schemas.update(IriSegment(mySchema3), projectRef, 2L, json"""{"a": "b"}""").rejectedWith[SchemaIsDeprecated]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource = sourceNoId.replace("minCount" -> 1, "wrong")
        schemas.update(IriSegment(mySchema), projectRef, 2L, wrongSource).rejectedWith[InvalidSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.update(IriSegment(mySchema), projectRef, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        schemas.update(IriSegment(mySchema), projectDeprecated.ref, 2L, source).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

      "reject if organization is deprecated" in {
        schemas.update(IriSegment(mySchema), projectWithDeprecatedOrg.ref, 2L, sourceNoId).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "tagging a schema" should {

      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$mySchema2"}"""
        val schema       = SchemaGen.schema(mySchema2, project.ref, sourceWithId, tags = Map(tag -> 1L))

        schemas.tag(IriSegment(mySchema2), projectRef, tag, 1L, 1L).accepted shouldEqual
          SchemaGen.resourceFor(schema, subject = subject, rev = 2L, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.tag(IriSegment(nxv + "other"), projectRef, tag, 1L, 1L).rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.tag(IriSegment(mySchema), projectRef, tag, 1L, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        schemas.tag(IriSegment(mySchema3), projectRef, tag, 2L, 2L).rejectedWith[SchemaIsDeprecated]
      }

      "reject if tag revision not found" in {
        schemas.tag(IriSegment(mySchema), projectRef, tag, 6L, 2L).rejected shouldEqual
          RevisionNotFound(provided = 6L, current = 2L)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.tag(IriSegment(mySchema), projectRef, tag, 2L, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        schemas.tag(IriSegment(mySchema), projectDeprecated.ref, tag, 2L, 2L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

      "reject if organization is deprecated" in {
        schemas.tag(IriSegment(mySchema), projectWithDeprecatedOrg.ref, tag, 1L, 2L).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }
    }

    "deprecating a schema" should {

      "succeed" in {
        schemas.deprecate(IriSegment(mySchema), projectRef, 2L).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, subject = subject, rev = 3L, deprecated = true, am = am, base = projBase)
      }

      "reject if it doesn't exists" in {
        schemas.deprecate(IriSegment(nxv + "other"), projectRef, 1L).rejectedWith[SchemaNotFound]
      }

      "reject if the revision passed is incorrect" in {
        schemas.deprecate(IriSegment(mySchema), projectRef, 5L).rejected shouldEqual
          IncorrectRev(provided = 5L, expected = 3L)
      }

      "reject if deprecated" in {
        schemas.deprecate(IriSegment(mySchema), projectRef, 3L).rejectedWith[SchemaIsDeprecated]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.deprecate(IriSegment(mySchema), projectRef, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if project is deprecated" in {
        schemas.deprecate(IriSegment(mySchema), projectDeprecated.ref, 1L).rejected shouldEqual
          WrappedProjectRejection(ProjectIsDeprecated(projectDeprecated.ref))
      }

      "reject if organization is deprecated" in {
        schemas.tag(IriSegment(mySchema), projectWithDeprecatedOrg.ref, tag, 1L, 2L).rejected shouldEqual
          WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated))
      }

    }

    "fetching a schema" should {
      val schema2 = SchemaGen.schema(mySchema2, project.ref, source deepMerge json"""{"@id": "$mySchema2"}""")

      "succeed" in {
        schemas.fetch(IriSegment(mySchema), projectRef).accepted shouldEqual
          SchemaGen.resourceFor(schemaUpdated, rev = 3L, deprecated = true, subject = subject, am = am, base = projBase)
      }

      "succeed by tag" in {
        schemas.fetchBy(IriSegment(mySchema2), projectRef, tag).accepted shouldEqual
          SchemaGen.resourceFor(schema2, subject = subject, am = am, base = projBase)
      }

      "succeed by rev" in {
        schemas.fetchAt(IriSegment(mySchema2), projectRef, 1L).accepted shouldEqual
          SchemaGen.resourceFor(schema2, subject = subject, am = am, base = projBase)
      }

      "reject if tag does not exist" in {
        val otherTag = TagLabel.unsafe("other")
        schemas.fetchBy(IriSegment(mySchema), projectRef, otherTag).rejected shouldEqual TagNotFound(otherTag)
      }

      "reject if revision does not exist" in {
        schemas.fetchAt(IriSegment(mySchema), projectRef, 5L).rejected shouldEqual
          RevisionNotFound(provided = 5L, current = 3L)
      }

      "fail fetching if schema does not exist" in {
        val mySchema = nxv + "notFound"
        schemas.fetch(IriSegment(mySchema), projectRef).rejectedWith[SchemaNotFound]
        schemas.fetchBy(IriSegment(mySchema), projectRef, tag).rejectedWith[SchemaNotFound]
        schemas.fetchAt(IriSegment(mySchema), projectRef, 2L).rejectedWith[SchemaNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        schemas.fetch(IriSegment(mySchema), projectRef).rejected shouldEqual
          WrappedProjectRejection(ProjectNotFound(projectRef))
      }

    }

    "fetching SSE" should {
      val allEvents = SSEUtils.list(
        mySchema  -> SchemaCreated,
        mySchema2 -> SchemaCreated,
        mySchema3 -> SchemaCreated,
        mySchema  -> SchemaUpdated,
        mySchema3 -> SchemaDeprecated,
        mySchema2 -> SchemaTagAdded,
        mySchema  -> SchemaDeprecated
      )

      "get the different events from start" in {
        val streams = List(
          schemas.events(NoOffset),
          schemas.events(org, NoOffset).accepted,
          schemas.events(projectRef, NoOffset).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take(allEvents.size.toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents
        }
      }

      "get the different events from offset 2" in {
        val streams = List(
          schemas.events(Sequence(2L)),
          schemas.events(org, Sequence(2L)).accepted,
          schemas.events(projectRef, Sequence(2L)).accepted
        )
        forAll(streams) { stream =>
          val events = stream
            .map { e => (e.event.id, e.eventType, e.offset) }
            .take((allEvents.size - 2).toLong)
            .compile
            .toList

          events.accepted shouldEqual allEvents.drop(2)
        }
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        schemas.events(projectRef, NoOffset).rejected shouldEqual WrappedProjectRejection(ProjectNotFound(projectRef))
      }

      "reject if organization does not exist" in {
        val org = Label.unsafe("other")
        schemas.events(org, NoOffset).rejected shouldEqual WrappedOrganizationRejection(OrganizationNotFound(org))
      }
    }
  }
}
