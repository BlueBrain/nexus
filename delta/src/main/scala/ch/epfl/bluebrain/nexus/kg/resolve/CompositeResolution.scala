package ch.epfl.bluebrain.nexus.kg.resolve
import cats.Monad
import cats.syntax.flatMap._
import ch.epfl.bluebrain.nexus.kg.resources.{Ref, Resource}

/**
  * Implementation that uses underlying resolutions in defined order to resolve resources.
  * @param  resolutions underlying resolutions
  * @tparam F the resolution effect type
  */
class CompositeResolution[F[_]](resolutions: List[Resolution[F]])(implicit F: Monad[F]) extends Resolution[F] {

  override def resolve(ref: Ref): F[Option[Resource]] =
    resolutions.foldLeft[F[Option[Resource]]](F.pure(None)) { (previousResult, resolution) =>
      previousResult.flatMap {
        case resolved @ Some(_) => F.pure(resolved)
        case None               => resolution.resolve(ref)
      }
    }
}

object CompositeResolution {

  /**
    * Constructs a [[CompositeResolution]] instance.
    *
    * @param resolutions underlying resolutions
    */
  final def apply[F[_]: Monad](resolutions: List[Resolution[F]]): CompositeResolution[F] =
    new CompositeResolution[F](resolutions)

  /**
    * Constructs a [[CompositeResolution]] instance.
    *
    * @param resolutions underlying resolutions
    */
  final def apply[F[_]: Monad](resolutions: Resolution[F]*): CompositeResolution[F] =
    new CompositeResolution[F](resolutions.toList)
}
