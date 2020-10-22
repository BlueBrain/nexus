package ch.epfl.bluebrain.nexus.delta.kernel.utils

@SuppressWarnings(Array("UnsafeTraversableMethods"))
trait ClassUtils {

  /**
    * @return the simple name of the class from the passed ''value''.
    */
  def simpleName[A](value: A): String =
    value.getClass.getSimpleName.split('$').head
}

object ClassUtils extends ClassUtils
