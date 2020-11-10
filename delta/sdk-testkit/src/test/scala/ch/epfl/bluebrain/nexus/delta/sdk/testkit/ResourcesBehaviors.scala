package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant
import java.util.UUID

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent.{ResourceCreated, ResourceDeprecated, ResourceTagAdded, ResourceUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Resources, SchemaResource}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

trait ResourcesBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CirceLiteral =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val shaclResolvedCtx                      = jsonContentOf("contexts/shacl.json")
  implicit def res: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  val project                               = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))
  val projectRef                            = project.value.ref

  val schemaSource = jsonContentOf("resources/schema.json")
  val schema1      = SchemaGen.schema(nxv + "myschema", project.value.ref, schemaSource)
  val schema2      = SchemaGen.schema(nxv + "myschema2", project.value.ref, schemaSource)

  def fetchSchema: ResourceRef => UIO[Option[SchemaResource]] = {
    case ref if ref.iri == schema1.id => UIO.pure(Some(SchemaGen.resourceFor(schema1)))
    case ref if ref.iri == schema2.id => UIO.pure(Some(SchemaGen.resourceFor(schema2, deprecated = true)))
    case _                            => UIO.pure(None)
  }

  def create: UIO[Resources]

  lazy val resources: Resources = create.accepted

  "The Resources operations bundle" when {

    // format: off
    val myId  = nxv + "myid"  // Resource created against the resource schema with id present on the payload
    val myId3 = nxv + "myid3" // Resource created against the resource schema with id present on the payload and passed explicitly
    val myId4 = nxv + "myid4" // Resource created against schema1 with id present on the payload and passed explicitly
    val myId5 = nxv + "myid5" // Resource created against the resource schema with id passed explicitly but not present on the payload
    val myId6 = nxv + "myid6" // Resource created against schema1 with id passed explicitly but not present on the payload
    // format: on
    val resourceSchema = Latest(schemas.resources)
    val myId2          = nxv + "myid2" // Resource created against the schema1 with id present on the payload
    val types          = Set(nxv + "Custom")
    val source         = jsonContentOf("resources/resource.json", "id" -> myId)
    val tag            = Label.unsafe("tag")

    "creating a resource" should {
      "succeed with the id present on the payload" in {
        forAll(List(myId -> resourceSchema, myId2 -> Latest(schema1.id))) { case (id, schema) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, schema)
          val resource     = resources.create(project.value, schema, sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(expectedData, types = types, subject = subject)
        }
      }

      "succeed with the id present on the payload and passed" in {
        forAll(List(myId3 -> resourceSchema, myId4 -> Latest(schema1.id))) { case (id, schema) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, schema)
          val resource     = resources.create(id, projectRef, schema, sourceWithId).accepted
          resource shouldEqual ResourceGen.resourceFor(expectedData, types = types, subject = subject)
        }
      }

      "succeed with the passed id" in {
        forAll(List(myId5 -> resourceSchema, myId6 -> Latest(schema1.id))) { case (id, schema) =>
          val sourceWithId    = source deepMerge json"""{"@id": "$id"}"""
          val sourceWithoutId = source.removeKeys(keywords.id)
          val expectedData    = ResourceGen.resource(id, projectRef, sourceWithId, schema).copy(source = sourceWithoutId)
          val resource        = resources.create(id, projectRef, schema, sourceWithoutId).accepted
          resource shouldEqual ResourceGen.resourceFor(expectedData, types = types, subject = subject)
        }
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        resources.create(otherId, projectRef, resourceSchema, source).rejected shouldEqual
          UnexpectedResourceId(id = otherId, payloadId = myId)
      }

      "reject if it already exists" in {
        resources.create(myId, projectRef, resourceSchema, source).rejected shouldEqual ResourceAlreadyExists(myId)
      }

      "reject if it does not validate against its schema" in {
        val otherId     = nxv + "other"
        val wrongSource = source deepMerge json"""{"@id": "$otherId", "number": "wrong"}"""
        resources.create(otherId, projectRef, Latest(schema1.id), wrongSource).rejectedWith[InvalidResource]
      }

      "reject if the validated schema is deprecated" in {
        val noIdSource = source.removeKeys(keywords.id)
        resources.create(nxv + "other", projectRef, Latest(schema2.id), noIdSource).rejectedWith[SchemaIsDeprecated]
      }

      "reject if the validated schema does not exists" in {
        val noIdSource = source.removeKeys(keywords.id)
        resources.create(nxv + "other", projectRef, Latest(nxv + "notExist"), noIdSource).rejectedWith[SchemaNotFound]
      }
    }

    "updating a resource" should {

      "succeed" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 60}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Latest(schema1.id))
        val resource     = resources.update(myId2, projectRef, Some(Latest(schema1.id)), 1L, updated).accepted
        resource shouldEqual ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 2L)
      }

      "succeed without specifying the schema" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 65}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Latest(schema1.id))
        val resource     = resources.update(myId2, projectRef, None, 2L, updated).accepted
        resource shouldEqual ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 3L)
      }

      "reject if it doesn't exists" in {
        resources.update(nxv + "other", projectRef, None, 1L, json"""{}""").rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.update(myId, projectRef, None, 3L, json"""{}""").rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 1L)
      }

      "reject if deprecated" in {
        resources.deprecate(myId3, projectRef, None, 1L).accepted
        resources.update(myId3, projectRef, None, 2L, json"""{}""").rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources
          .update(myId2, projectRef, Some(resourceSchema), 3L, json"""{}""")
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource = source.removeKeys(keywords.id) deepMerge json"""{"number": "wrong"}"""
        resources.update(myId2, projectRef, Some(Latest(schema1.id)), 3L, wrongSource).rejectedWith[InvalidResource]
      }
    }

    "tagging a resource" should {

      "succeed" in {
        val expectedData = ResourceGen.resource(myId, projectRef, source, resourceSchema)
        val resource     = resources.tag(myId, projectRef, Some(resourceSchema), tag, 1L, 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(expectedData, tags = Map(tag -> 1L), types = types, subject = subject, rev = 2L)
      }

      "reject if it doesn't exists" in {
        resources.tag(nxv + "other", projectRef, None, tag, 1L, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.tag(myId, projectRef, None, tag, 1L, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.tag(myId3, projectRef, None, tag, 2L, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources.tag(myId2, projectRef, Some(resourceSchema), tag, 2L, 3L).rejectedWith[UnexpectedResourceSchema]
      }

      "reject if tag revision not found" in {
        resources.tag(myId, projectRef, Some(resourceSchema), tag, 6L, 2L).rejected shouldEqual
          RevisionNotFound(provided = 6L, current = 2L)
      }
    }

    "deprecating a resource" should {

      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$myId4"}"""
        val expectedData = ResourceGen.resource(myId4, projectRef, sourceWithId, Latest(schema1.id))
        val resource     = resources.deprecate(myId4, projectRef, Some(Latest(schema1.id)), 1L).accepted
        resource shouldEqual
          ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 2L, deprecated = true)
      }

      "reject if it doesn't exists" in {
        resources.deprecate(nxv + "other", projectRef, None, 1L).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.deprecate(myId, projectRef, None, 3L).rejected shouldEqual
          IncorrectRev(provided = 3L, expected = 2L)
      }

      "reject if deprecated" in {
        resources.deprecate(myId4, projectRef, None, 2L).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources.deprecate(myId2, projectRef, Some(resourceSchema), 3L).rejectedWith[UnexpectedResourceSchema]
      }

    }

    "fetching a resource" should {
      val expectedData = ResourceGen.resource(myId, projectRef, source, resourceSchema)

      "succeed" in {
        forAll(List(None, Some(resourceSchema))) { schema =>
          resources.fetch(myId, projectRef, schema).accepted.value shouldEqual
            ResourceGen.resourceFor(expectedData, tags = Map(tag -> 1L), types = types, subject = subject, rev = 2L)
        }
      }

      "succeed by tag" in {
        forAll(List(None, Some(resourceSchema))) { schema =>
          resources.fetchBy(myId, projectRef, schema, tag).accepted.value shouldEqual
            ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 1L)
        }
      }

      "succeed by rev" in {
        forAll(List(None, Some(resourceSchema))) { schema =>
          resources.fetchAt(myId, projectRef, schema, 1L).accepted.value shouldEqual
            ResourceGen.resourceFor(expectedData, types = types, subject = subject, rev = 1L)
        }
      }

      "reject if tag does not exist" in {
        val otherTag = Label.unsafe("other")
        forAll(List(None, Some(resourceSchema))) { schema =>
          resources.fetchBy(myId, projectRef, schema, otherTag).rejected shouldEqual TagNotFound(otherTag)
        }
      }

      "reject if revision does not exist" in {
        forAll(List(None, Some(resourceSchema))) { schema =>
          resources.fetchAt(myId, projectRef, schema, 5L).rejected shouldEqual
            RevisionNotFound(provided = 5L, current = 2L)
        }
      }

      "return none if resource does not exist" in {
        val myId = nxv + "notFound"
        resources.fetch(myId, projectRef, None).accepted shouldEqual None
        resources.fetchBy(myId, projectRef, None, tag).accepted shouldEqual None
        resources.fetchAt(myId, projectRef, None, 2L).accepted shouldEqual None
      }

      "return none if schema is not resource schema" in {
        resources.fetch(myId, projectRef, Some(Latest(schema1.id))).accepted shouldEqual None
        resources.fetchBy(myId, projectRef, Some(Latest(schema1.id)), tag).accepted shouldEqual None
        resources.fetchAt(myId, projectRef, Some(Latest(schema1.id)), 2L).accepted shouldEqual None
      }
    }

    "fetching SSE" should {
      val allEvents = List(
        (myId, ClassUtils.simpleName(ResourceCreated), Sequence(1L)),
        (myId2, ClassUtils.simpleName(ResourceCreated), Sequence(2L)),
        (myId3, ClassUtils.simpleName(ResourceCreated), Sequence(3L)),
        (myId4, ClassUtils.simpleName(ResourceCreated), Sequence(4L)),
        (myId5, ClassUtils.simpleName(ResourceCreated), Sequence(5L)),
        (myId6, ClassUtils.simpleName(ResourceCreated), Sequence(6L)),
        (myId2, ClassUtils.simpleName(ResourceUpdated), Sequence(7L)),
        (myId2, ClassUtils.simpleName(ResourceUpdated), Sequence(8L)),
        (myId3, ClassUtils.simpleName(ResourceDeprecated), Sequence(9L)),
        (myId, ClassUtils.simpleName(ResourceTagAdded), Sequence(10L)),
        (myId4, ClassUtils.simpleName(ResourceDeprecated), Sequence(11L))
      )

      "get the different events from start" in {
        val events = resources
          .events()
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(11L)
          .compile
          .toList

        events.accepted shouldEqual allEvents
      }

      "get the different current events from start" in {
        val events = resources
          .currentEvents()
          .map { e => (e.event.id, e.eventType, e.offset) }
          .compile
          .toList

        events.accepted shouldEqual allEvents
      }

      "get the different events from offset 2" in {
        val events = resources
          .events(Sequence(2L))
          .map { e => (e.event.id, e.eventType, e.offset) }
          .take(9L)
          .compile
          .toList

        events.accepted shouldEqual allEvents.drop(2)
      }

      "get the different current events from offset 2" in {
        val events = resources
          .currentEvents(Sequence(2L))
          .map { e => (e.event.id, e.eventType, e.offset) }
          .compile
          .toList

        events.accepted shouldEqual allEvents.drop(2)
      }
    }
  }
}
