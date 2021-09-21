package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import scala.reflect.ClassTag

trait ClassTagSyntax {
  implicit final def classTagSyntax[A](classTag: ClassTag[A]): ClassTagOps[A] = new ClassTagOps(classTag)
}

final class ClassTagOps[A](private val classTag: ClassTag[A]) extends AnyVal {

  /**
    * @return
    *   the simple name of the class from the implicitly available [[ClassTag]] instance.
    */
  def simpleName: String = classTag.runtimeClass.getSimpleName

}
