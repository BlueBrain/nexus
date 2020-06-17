package ch.epfl.bluebrain.nexus.kg.resources

import cats.data.EitherT
import cats.effect.Effect
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json

class Tags[F[_]: Effect](repo: Repo[F]) {

  /**
    * Tags a view. This operation aliases the provided ''targetRev'' with the  provided ''tag''.
    *
    * @param id     the id of the view
    * @param rev    the last known revision of the view
    * @param source the json payload which contains the targetRev and the tag
    * @param schema the schema reference that constrains the resource
    * @return Some(resource) in the F context when found and None in the F context when not found
    */
  def create(id: ResId, rev: Long, source: Json, schema: Ref)(implicit subject: Subject): RejOrResource[F] =
    EitherT.fromEither[F](Tag(id, source.addContext(tagCtxUri))).flatMap { tag =>
      repo.tag(id, schema, rev, tag.rev, tag.value)
    }

  /**
    * Fetches the latest revision of a view tags.
    *
    * @param id     the id of the resource
    * @param schema the schema reference that constrains the resource
    * @return Some(tags) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, schema: Ref): RejOrTags[F] =
    repo.get(id, Some(schema)).toRight(notFound(id.ref)).map(toTags)

  /**
    * Fetches the provided revision of a view tags.
    *
    * @param id     the id of the view
    * @param rev    the revision of the view
    * @param schema the schema reference that constrains the resource
    * @return Some(tags) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, rev: Long, schema: Ref): RejOrTags[F] =
    repo.get(id, rev, Some(schema)).toRight(notFound(id.ref, Some(rev))).map(toTags)

  /**
    * Fetches the provided tag of a view tags.
    *
    * @param id     the id of the view
    * @param tag    the tag of the view
    * @param schema the schema reference that constrains the resource
    * @return Some(tags) in the F context when found and None in the F context when not found
    */
  def fetch(id: ResId, tag: String, schema: Ref): RejOrTags[F] =
    repo.get(id, tag, Some(schema)).toRight(notFound(id.ref, tag = Some(tag))).map(toTags)

  private def toTags(resource: Resource): TagSet =
    resource.tags.map { case (value, tagRev) => Tag(tagRev, value) }.toSet

}

object Tags {

  /**
    * @tparam F the monadic effect type
    * @return a new [[Tags]] for the provided F type
    */
  final def apply[F[_]: Effect](implicit repo: Repo[F]): Tags[F] =
    new Tags[F](repo)
}
