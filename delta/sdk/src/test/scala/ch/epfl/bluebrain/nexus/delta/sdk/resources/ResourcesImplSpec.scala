package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.NexusSource.DecodingOption
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, DataResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.CancelAfterFailure

import java.util.UUID

class ResourcesImplSpec
    extends CatsEffectSpec
    with DoobieScalaTestFixture
    with CancelAfterFailure
    with CirceLiteral
    with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val api: JsonLdApi = JsonLdJavaApi.strict

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.metadata        -> ContextValue.fromFile("contexts/metadata.json"),
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json")
    )

  private val org               = Label.unsafe("myorg")
  private val am                = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  private val projBase          = nxv.base
  private val project           = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  private val projectDeprecated = ProjectGen.project("myorg", "myproject2")
  private val projectRef        = project.ref
  private val allApiMappings    = am + Resources.mappings

  private val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
  private val schema1      = SchemaGen.schema(nxv + "myschema", project.ref, schemaSource.removeKeys(keywords.id))
  private val schema2      = SchemaGen.schema(schema.Person, project.ref, schemaSource.removeKeys(keywords.id))
  private val schema3      = SchemaGen.schema(nxv + "myschema3", project.ref, schemaSource.removeKeys(keywords.id))

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, _) if ref.iri == schema2.id => IO.pure(Some(SchemaGen.resourceFor(schema2, deprecated = true)))
    case (ref, _) if ref.iri == schema1.id => IO.pure(Some(SchemaGen.resourceFor(schema1)))
    case (ref, _) if ref.iri == schema3.id => IO.pure(Some(SchemaGen.resourceFor(schema3)))
    case _                                 => IO.none
  }
  private val resourceResolution: ResourceResolution[Schema]                  =
    ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

  private val fetchContext = FetchContextDummy(
    Map(
      project.ref           -> project.context.copy(apiMappings = allApiMappings),
      projectDeprecated.ref -> projectDeprecated.context
    ),
    Set(projectDeprecated.ref),
    ProjectContextRejection
  )
  private val config       = ResourcesConfig(eventLogConfig, DecodingOption.Strict)

  private val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (r, p, _) => resources.fetch(r, p).attempt.map(_.left.map(_ => ResourceResolutionReport()))
  )

  private lazy val resources: Resources = ResourcesImpl(
    ValidateResource(resourceResolution),
    fetchContext,
    resolverContextResolution,
    config,
    xas
  )

  "The Resources operations bundle" when {

    // format: off
    val myId  = nxv + "myid"  // Resource created against the resource schema with id present on the payload
    val myId3 = nxv + "myid3" // Resource created against the resource schema with id present on the payload and passed explicitly
    val myId4 = nxv + "myid4" // Resource created against schema1 with id present on the payload and passed explicitly
    val myId5 = nxv + "myid5" // Resource created against the resource schema with id passed explicitly but not present on the payload
    val myId6 = nxv + "myid6" // Resource created against schema1 with id passed explicitly but not present on the payload
    val myId7 = nxv + "myid7" // Resource created against the resource schema with id passed explicitly and with payload without @context
    val myId8  = nxv + "myid8" // Resource created against the resource schema with id present on the payload and having its context pointing on metadata and myId1 and myId2
    val myId9  = nxv + "myid9" // Resource created against the resource schema with id present on the payload and having its context pointing on metadata and myId8 so therefore myId1 and myId2
    val myId10 = nxv + "myid10"
    val myId11 = nxv + "myid11"
    val myId12 = nxv + "myid12"
    val myId13 = nxv + "myid13"

    // format: on
    val resourceSchema    = Latest(schemas.resources)
    val myId2             = nxv + "myid2" // Resource created against the schema1 with id present on the payload
    val types             = Set(nxv + "Custom")
    val source            = jsonContentOf("resources/resource.json", "id" -> myId)
    def sourceWithBlankId = source deepMerge json"""{"@id": ""}"""
    val tag               = UserTag.unsafe("tag")

    def mkResource(res: Resource): DataResource =
      ResourceGen.resourceFor(res, types = types, subject = subject)

    "creating a resource" should {
      "succeed with the id present on the payload" in {
        forAll(List(myId -> resourceSchema, myId2 -> Latest(schema1.id))) { case (id, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
          val resource     = resources.create(projectRef, schemaRef, sourceWithId, None).accepted
          resource shouldEqual mkResource(expectedData)
        }
      }

      "succeed and tag with the id present on the payload" in {
        forAll(List(myId10 -> resourceSchema, myId11 -> Latest(schema1.id))) { case (id, schemaRef) =>
          val sourceWithId     = source deepMerge json"""{"@id": "$id"}"""
          val expectedData     =
            ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1), Tags(tag -> 1))
          val expectedResource = mkResource(expectedData)

          val resource = resources.create(projectRef, schemaRef, sourceWithId, Some(tag)).accepted

          val resourceByTag = resources.fetch(IdSegmentRef(id, tag), projectRef, Some(schemaRef)).accepted
          resource shouldEqual expectedResource
          resourceByTag shouldEqual expectedResource
        }
      }

      "succeed with the id present on the payload and passed" in {
        val list =
          List(
            (myId3, "_", resourceSchema),
            (myId4, "myschema", Latest(schema1.id))
          )
        forAll(list) { case (id, schemaSegment, schemaRef) =>
          val sourceWithId = source deepMerge json"""{"@id": "$id"}"""
          val expectedData = ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
          val resource     = resources.create(id, projectRef, schemaSegment, sourceWithId, None).accepted
          resource shouldEqual mkResource(expectedData)
        }
      }

      "succeed and tag with the id present on the payload and passed" in {
        val list =
          List(
            (myId12, "_", resourceSchema),
            (myId13, "myschema", Latest(schema1.id))
          )
        forAll(list) { case (id, schemaSegment, schemaRef) =>
          val sourceWithId     = source deepMerge json"""{"@id": "$id"}"""
          val expectedData     =
            ResourceGen.resource(id, projectRef, sourceWithId, Revision(schemaRef.iri, 1), Tags(tag -> 1))
          val expectedResource = mkResource(expectedData)

          val resource = resources.create(id, projectRef, schemaSegment, sourceWithId, Some(tag)).accepted

          val resourceByTag = resources.fetch(IdSegmentRef(id, tag), projectRef, Some(schemaRef)).accepted
          resource shouldEqual expectedResource
          resourceByTag shouldEqual expectedResource
        }
      }

      "succeed with the passed id" in {
        val list = List(
          ("nxv:myid5", myId5, resourceSchema),
          ("nxv:myid6", myId6, Latest(schema1.id))
        )
        forAll(list) { case (segment, iri, schemaRef) =>
          val sourceWithId    = source deepMerge json"""{"@id": "$iri"}"""
          val sourceWithoutId = source.removeKeys(keywords.id)
          val expectedData    =
            ResourceGen
              .resource(iri, projectRef, sourceWithId, Revision(schemaRef.iri, 1))
              .copy(source = sourceWithoutId)
          val resource        = resources.create(segment, projectRef, schemaRef, sourceWithoutId, None).accepted
          resource shouldEqual mkResource(expectedData)
        }
      }

      "succeed with payload without @context" in {
        val payload        = json"""{"name": "Alice"}"""
        val payloadWithCtx =
          payload.addContext(json"""{"@context": {"@vocab": "${nxv.base}","@base": "${nxv.base}"}}""")
        val schemaRev      = Revision(resourceSchema.iri, 1)
        val expectedData   =
          ResourceGen.resource(myId7, projectRef, payloadWithCtx, schemaRev).copy(source = payload)

        resources.create(myId7, projectRef, schemas.resources, payload, None).accepted shouldEqual
          ResourceGen.resourceFor(expectedData, subject = subject)
      }

      "succeed with the id present on the payload and pointing to another resource in its context" in {
        val sourceMyId8  =
          source.addContext(contexts.metadata).addContext(myId).addContext(myId2) deepMerge json"""{"@id": "$myId8"}"""
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData =
          ResourceGen.resource(myId8, projectRef, sourceMyId8, schemaRev)(resolverContextResolution(projectRef))
        val resource     = resources.create(projectRef, resourceSchema, sourceMyId8, None).accepted
        resource shouldEqual mkResource(expectedData)
      }

      "succeed when pointing to another resource which itself points to other resources in its context" in {
        val sourceMyId9  = source.addContext(contexts.metadata).addContext(myId8) deepMerge json"""{"@id": "$myId9"}"""
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData =
          ResourceGen.resource(myId9, projectRef, sourceMyId9, schemaRev)(resolverContextResolution(projectRef))
        val resource     = resources.create(projectRef, resourceSchema, sourceMyId9, None).accepted
        resource shouldEqual mkResource(expectedData)
      }

      "reject with different ids on the payload and passed" in {
        val otherId = nxv + "other"
        resources.create(otherId, projectRef, schemas.resources, source, None).rejected shouldEqual
          UnexpectedResourceId(id = otherId, payloadId = myId)
      }

      "reject if the id is blank" in {
        resources.create(projectRef, schemas.resources, sourceWithBlankId, None).rejected shouldEqual
          BlankResourceId
      }

      "reject with ReservedResourceId" in {
        forAll(List(Latest(schemas.resources), Latest(schema1.id))) { schemaRef =>
          val myId                 = contexts + "some.json"
          val sourceWithReservedId = source deepMerge json"""{"@id": "$myId"}"""
          resources.create(myId, projectRef, schemaRef, sourceWithReservedId, None).rejectedWith[ReservedResourceId]
        }
      }

      "reject if it already exists" in {
        resources.create(myId, projectRef, schemas.resources, source, None).rejected shouldEqual
          ResourceAlreadyExists(myId, projectRef)

        resources
          .create("nxv:myid", projectRef, schemas.resources, source, None)
          .rejected shouldEqual
          ResourceAlreadyExists(myId, projectRef)
      }

      "reject if it does not validate against its schema" in {
        val otherId     = nxv + "other"
        val wrongSource = source deepMerge json"""{"@id": "$otherId", "number": "wrong"}"""
        resources.create(otherId, projectRef, schema1.id, wrongSource, None).rejectedWith[InvalidResource]
      }

      "reject if the validated schema is deprecated" in {
        val otherId    = nxv + "other"
        val noIdSource = source.removeKeys(keywords.id)
        forAll(List[IdSegment](schema2.id, "Person")) { segment =>
          resources.create(otherId, projectRef, segment, noIdSource, None).rejected shouldEqual
            SchemaIsDeprecated(schema2.id)

        }
      }

      "reject if the validated schema does not exists" in {
        val otherId    = nxv + "other"
        val noIdSource = source.removeKeys(keywords.id)
        resources.create(otherId, projectRef, "nxv:notExist", noIdSource, None).rejected shouldEqual
          InvalidSchemaRejection(
            Latest(nxv + "notExist"),
            project.ref,
            ResourceResolutionReport(
              ResolverReport.failed(
                nxv + "in-project",
                project.ref -> ResolverResolutionRejection.ResourceNotFound(nxv + "notExist", project.ref)
              )
            )
          )
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))
        resources.create(projectRef, schemas.resources, source, None).rejectedWith[ProjectContextRejection]

        resources.create(myId, projectRef, schemas.resources, source, None).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        resources.create(projectDeprecated.ref, schemas.resources, source, None).rejectedWith[ProjectContextRejection]

        resources
          .create(myId, projectDeprecated.ref, schemas.resources, source, None)
          .rejectedWith[ProjectContextRejection]
      }

      "reject if part of the context can't be resolved" in {
        val myIdX           = nxv + "myidx"
        val unknownResource = nxv + "fail"
        val sourceMyIdX     =
          source.addContext(contexts.metadata).addContext(unknownResource) deepMerge json"""{"@id": "$myIdX"}"""
        resources.create(projectRef, resourceSchema, sourceMyIdX, None).rejectedWith[InvalidJsonLdFormat]
      }

      "reject for an incorrect payload" in {
        val myIdX       = nxv + "myidx"
        val sourceMyIdX =
          source.addContext(
            contexts.metadata
          ) deepMerge json"""{"other": {"@id": " http://nexus.example.com/myid"}}""" deepMerge json"""{"@id": "$myIdX"}"""
        resources.create(projectRef, resourceSchema, sourceMyIdX, None).rejectedWith[InvalidJsonLdFormat]
      }
    }

    "updating a resource" should {

      "succeed" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 60}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Revision(schema1.id, 1))
        resources.update(myId2, projectRef, Some(schema1.id), 1, updated).accepted shouldEqual
          mkResource(expectedData).copy(rev = 2)
      }

      "succeed without specifying the schema" in {
        val updated      = source.removeKeys(keywords.id) deepMerge json"""{"number": 65}"""
        val expectedData = ResourceGen.resource(myId2, projectRef, updated, Revision(schema1.id, 1))
        resources.update("nxv:myid2", projectRef, None, 2, updated).accepted shouldEqual
          mkResource(expectedData).copy(rev = 3)
      }

      "succeed when changing the schema" in {
        val updatedSource   = source.removeKeys(keywords.id) deepMerge json"""{"number": 70}"""
        val newSchema       = Revision(schema3.id, 1)
        val updatedResource = resources.update(myId2, projectRef, Some(newSchema.iri), 3, updatedSource).accepted

        updatedResource.rev shouldEqual 4
        updatedResource.schema shouldEqual newSchema
      }

      "reject if it doesn't exists" in {
        resources
          .update(nxv + "other", projectRef, None, 1, json"""{"a": "b"}""")
          .rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.update(myId, projectRef, None, 3, json"""{"a": "b"}""").rejected shouldEqual
          IncorrectRev(provided = 3, expected = 1)
      }

      "reject if deprecated" in {
        resources.deprecate(myId3, projectRef, None, 1).accepted
        resources
          .update(myId3, projectRef, None, 2, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
        resources
          .update("nxv:myid3", projectRef, None, 2, json"""{"a": "b"}""")
          .rejectedWith[ResourceIsDeprecated]
      }

      "reject if it does not validate against its schema" in {
        val wrongSource = source.removeKeys(keywords.id) deepMerge json"""{"number": "wrong"}"""
        resources.update(myId2, projectRef, Some(schema1.id), 4, wrongSource).rejectedWith[InvalidResource]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.update(myId, projectRef, None, 2, source).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        resources.update(myId, projectDeprecated.ref, None, 2, source).rejectedWith[ProjectContextRejection]
      }
    }

    "refreshing a resource" should {

      "succeed" in {
        val expectedData =
          ResourceGen.resource(myId6, projectRef, source.removeKeys(keywords.id), Revision(schema1.id, 1))
        resources.refresh(myId6, projectRef, Some(schema1.id)).accepted shouldEqual
          mkResource(expectedData).copy(rev = 2)
      }

      "succeed without specifying the schema" in {
        val expectedData =
          ResourceGen.resource(myId6, projectRef, source.removeKeys(keywords.id), Revision(schema1.id, 1))
        resources.refresh("nxv:myid6", projectRef, None).accepted shouldEqual
          ResourceGen.resourceFor(
            expectedData,
            types = types,
            subject = subject,
            rev = 3
          )
      }

      "reject if it doesn't exists" in {
        resources
          .refresh(nxv + "other", projectRef, None)
          .rejectedWith[ResourceNotFound]
      }

      "reject if schemas do not match" in {
        resources
          .refresh(myId6, projectRef, Some(schemas.resources))
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.refresh(myId6, projectRef, None).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        resources.refresh(myId6, projectDeprecated.ref, None).rejectedWith[ProjectContextRejection]
      }

      "reject if deprecated" in {
        resources.deprecate(myId6, projectRef, None, 3).accepted
        resources
          .refresh(myId6, projectRef, None)
          .rejectedWith[ResourceIsDeprecated]
        resources
          .refresh("nxv:myid6", projectRef, None)
          .rejectedWith[ResourceIsDeprecated]
      }
    }

    "updating a resource schema" should {

      val id           = nxv + "schemaUpdate"
      val sourceWithId = source deepMerge json"""{"@id": "$id"}"""

      val nonExistentResourceId = iri"http://does.not.exist"
      val nonExistentSchemaId   = iri"http://does.not.exist"
      val nonExistentProject    = ProjectRef(Label.unsafe("not"), Label.unsafe("there"))

      "reject if the resource doesn't exist" in {
        resources
          .updateResourceSchema(nonExistentResourceId, projectRef, schema3.id)
          .rejectedWith[ResourceNotFound]
      }

      "reject if the schema doesn't exist" in {
        resources.create(id, projectRef, schema1.id, sourceWithId, None).accepted
        resources
          .updateResourceSchema(id, projectRef, nonExistentSchemaId)
          .rejectedWith[InvalidSchemaRejection]
      }

      "refresh if the provided schema is the existing resource schema" in {
        val refreshed = resources.updateResourceSchema(id, projectRef, schema1.id).accepted
        refreshed.schema.iri shouldEqual schema1.id
      }

      "reject if the project doesn't exist" in {
        resources
          .updateResourceSchema(id, nonExistentProject, schema3.id)
          .rejectedWith[ProjectContextRejection]
      }

      "succeed" in {
        val updated = resources.updateResourceSchema(id, projectRef, schema3.id).accepted
        updated.schema.iri shouldEqual schema3.id

        val fetched = resources.fetch(id, projectRef, None).accepted
        fetched.schema.iri shouldEqual schema3.id
      }

    }

    "tagging a resource" should {

      "succeed" in {
        val schemaRev          = Revision(resourceSchema.iri, 1)
        val expectedData       = ResourceGen.resource(myId, projectRef, source, schemaRev, tags = Tags(tag -> 1))
        val expectedLatestRev  = mkResource(expectedData).copy(rev = 2)
        val expectedTaggedData = expectedData.copy(tags = Tags.empty)
        val expectedTaggedRev  = mkResource(expectedTaggedData).copy(rev = 1)

        val resource =
          resources.tag(myId, projectRef, Some(schemas.resources), tag, 1, 1).accepted

        // Lookup by tag should return the tagged rev but have no tags in the data
        val taggedRevision =
          resources.fetch(IdSegmentRef(myId, tag), projectRef, Some(schemas.resources)).accepted
        // Lookup by latest revision should return rev 2 with tags in the data
        val latestRevision =
          resources.fetch(ResourceRef(myId), projectRef).accepted

        resource shouldEqual expectedLatestRev
        latestRevision shouldEqual expectedLatestRev
        taggedRevision shouldEqual expectedTaggedRev
      }

      "reject if it doesn't exists" in {
        resources.tag(nxv + "other", projectRef, None, tag, 1, 1).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.tag(myId, projectRef, None, tag, 1, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 2)
      }

      "succeed if deprecated" in {
        resources.tag(myId3, projectRef, None, tag, 2, 2).accepted
      }

      "reject if schemas do not match" in {
        resources
          .tag(myId2, projectRef, Some(schemas.resources), tag, 2, 4)
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if tag revision not found" in {
        resources
          .tag(myId, projectRef, Some(schemas.resources), tag, 6, 2)
          .rejected shouldEqual
          RevisionNotFound(provided = 6, current = 2)
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.tag(myId, projectRef, None, tag, 2, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        resources.tag(myId, projectDeprecated.ref, None, tag, 2, 1).rejectedWith[ProjectContextRejection]
      }
    }

    "deprecating a resource" should {

      "succeed" in {
        val sourceWithId = source deepMerge json"""{"@id": "$myId4"}"""
        val expectedData = ResourceGen.resource(myId4, projectRef, sourceWithId, Revision(schema1.id, 1))
        val resource     = resources.deprecate(myId4, projectRef, Some(schema1.id), 1).accepted
        resource shouldEqual mkResource(expectedData).copy(rev = 2, deprecated = true)
      }

      "reject if it doesn't exists" in {
        resources.deprecate(nxv + "other", projectRef, None, 1).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.deprecate(myId, projectRef, None, 3).rejected shouldEqual
          IncorrectRev(provided = 3, expected = 2)
      }

      "reject if deprecated" in {
        resources.deprecate(myId4, projectRef, None, 2).rejectedWith[ResourceIsDeprecated]
        resources.deprecate("nxv:myid4", projectRef, None, 2).rejectedWith[ResourceIsDeprecated]
      }

      "reject if schemas do not match" in {
        resources.deprecate(myId2, projectRef, Some(schemas.resources), 4).rejectedWith[UnexpectedResourceSchema]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.deprecate(myId, projectRef, None, 1).rejectedWith[ProjectContextRejection]
      }

      "reject if project is deprecated" in {
        resources.deprecate(myId, projectDeprecated.ref, None, 1).rejectedWith[ProjectContextRejection]
      }

    }

    "fetching a resource" should {
      val schemaRev          = Revision(resourceSchema.iri, 1)
      val expectedData       = ResourceGen.resource(myId, projectRef, source, schemaRev)
      val expectedDataLatest = expectedData.copy(tags = Tags(tag -> 1))

      "succeed" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(myId, projectRef, schema).accepted shouldEqual
            mkResource(expectedDataLatest).copy(rev = 2)
        }
      }

      "succeed by tag" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef("nxv:myid", tag), projectRef, schema).accepted shouldEqual
            mkResource(expectedData)
        }
      }

      "succeed by rev" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, 1), projectRef, schema).accepted shouldEqual
            mkResource(expectedData)
        }
      }

      "reject if tag does not exist" in {
        val otherTag = UserTag.unsafe("other")
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, otherTag), projectRef, schema).rejected shouldEqual TagNotFound(otherTag)
        }
      }

      "reject if revision does not exist" in {
        forAll(List[Option[IdSegment]](None, Some(schemas.resources))) { schema =>
          resources.fetch(IdSegmentRef(myId, 5), projectRef, schema).rejected shouldEqual
            RevisionNotFound(provided = 5, current = 2)
        }
      }

      "fail fetching if resource does not exist" in {
        val myId = nxv + "notFound"
        resources.fetch(myId, projectRef, None).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, tag), projectRef, None).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, 2), projectRef, None).rejectedWith[ResourceNotFound]
      }

      "fail fetching if schema is not resource schema" in {
        resources.fetch(myId, projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, tag), projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
        resources.fetch(IdSegmentRef(myId, 2), projectRef, Some(schema1.id)).rejectedWith[ResourceNotFound]
      }

      "reject if project does not exist" in {
        val projectRef = ProjectRef(org, Label.unsafe("other"))

        resources.fetch(myId, projectRef, None).rejectedWith[ProjectContextRejection]
      }

      "fail fetching if resource does not exist on deprecated project" in {
        resources.fetch(myId, projectDeprecated.ref, None).rejectedWith[ResourceNotFound]
      }
    }

    "deleting a tag" should {
      "succeed" in {
        val schemaRev    = Revision(resourceSchema.iri, 1)
        val expectedData = ResourceGen.resource(myId, projectRef, source, schemaRev)
        val resource     =
          resources.deleteTag(myId, projectRef, Some(schemas.resources), tag, 2).accepted
        resource shouldEqual mkResource(expectedData).copy(rev = 3)
      }

      "reject if the resource doesn't exists" in {
        resources.deleteTag(nxv + "other", projectRef, None, tag, 1).rejectedWith[ResourceNotFound]
      }

      "reject if the revision passed is incorrect" in {
        resources.deleteTag(myId, projectRef, None, tag, 4).rejected shouldEqual
          IncorrectRev(provided = 4, expected = 3)
      }

      "reject if schemas do not match" in {
        resources
          .deleteTag(myId2, projectRef, Some(schemas.resources), tag, 4)
          .rejectedWith[UnexpectedResourceSchema]
      }

      "reject if the tag doesn't exist" in {
        resources.deleteTag(myId, projectRef, Some(schemas.resources), tag, 3).rejectedWith[TagNotFound]
      }
    }
  }
}
