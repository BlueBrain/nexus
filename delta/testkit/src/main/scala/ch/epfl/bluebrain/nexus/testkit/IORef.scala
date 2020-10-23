package ch.epfl.bluebrain.nexus.testkit

import cats.data.State
import cats.effect.concurrent.Ref
import monix.bio.{Task, UIO}

// $COVERAGE-OFF$
/**
  * A bifunctor ref fixed in IO.
  */
trait IORef[A] {

  /**
    * Obtains the current value.
    *
    * Since `Ref` is always guaranteed to have a value, the returned action
    * completes immediately after being bound.
    */
  def get: UIO[A]

  /**
    * Sets the current value to `a`.
    *
    * The returned action completes after the reference has been successfully set.
    *
    * Satisfies:
    *   `r.set(fa) *> r.get == fa`
    */
  def set(a: A): UIO[Unit]

  /**
    * Updates the current value using `f` and returns the value that was updated.
    *
    * In case of retries caused by concurrent modifications,
    * the returned value will be the last one before a successful update.
    */
  def getAndUpdate(f: A => A): UIO[A] =
    modify { a =>
      (f(a), a)
    }

  /**
    * Replaces the current value with `a`, returning the previous value.
    */
  def getAndSet(a: A): UIO[A] = getAndUpdate(_ => a)

  /**
    * Updates the current value using `f`, and returns the updated value.
    */
  def updateAndGet(f: A => A): UIO[A] =
    modify { a =>
      val newA = f(a)
      (newA, newA)
    }

  /**
    * Obtains a snapshot of the current value, and a setter for updating it.
    * The setter may noop (in which case `false` is returned) if another concurrent
    * call to `access` uses its setter first.
    *
    * Once it has noop'd or been used once, a setter never succeeds again.
    *
    * Satisfies:
    *   `r.access.map(_._1) == r.get`
    *   `r.access.flatMap { case (v, setter) => setter(f(v)) } == r.tryUpdate(f).map(_.isDefined)`
    */
  def access: UIO[(A, A => UIO[Boolean])]

  /**
    * Attempts to modify the current value once, returning `false` if another
    * concurrent modification completes between the time the variable is
    * read and the time it is set.
    */
  def tryUpdate(f: A => A): UIO[Boolean]

  /**
    * Like `tryUpdate` but allows the update function to return an output value of
    * type `B`. The returned action completes with `None` if the value is not updated
    * successfully and `Some(b)` otherwise.
    */
  def tryModify[B](f: A => (A, B)): UIO[Option[B]]

  /**
    * Modifies the current value using the supplied update function. If another modification
    * occurs between the time the current value is read and subsequently updated, the modification
    * is retried using the new value. Hence, `f` may be invoked multiple times.
    *
    * Satisfies:
    *   `r.update(_ => a) == r.set(a)`
    */
  def update(f: A => A): UIO[Unit]

  /**
    * Like `tryModify` but does not complete until the update has been successfully made.
    */
  def modify[B](f: A => (A, B)): UIO[B]

  /**
    * Update the value of this ref with a state computation.
    *
    * The current value of this ref is used as the initial state and the computed output state
    * is stored in this ref after computation completes. If a concurrent modification occurs,
    * `None` is returned.
    */
  def tryModifyState[B](state: State[A, B]): UIO[Option[B]]

  /**
    * Like [[tryModifyState]] but retries the modification until successful.
    */
  def modifyState[B](state: State[A, B]): UIO[B]
}

object IORef {

  /**
    * Like `apply` but returns the newly allocated ref directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    *
    * @see [[Ref.unsafe]]
    */
  final def unsafe[A](a: A): IORef[A] =
    fromTask(Ref.unsafe(a))

  /**
    * Creates an asynchronous, concurrent mutable reference initialized to the supplied value.
    *
    * @see [[Ref.of]]
    */
  final def of[A](a: A): UIO[IORef[A]] =
    Ref[Task].of(a).hideErrors.map(fromTask)

  /**
    * Lifts a [[Ref]] into an [[IORef]].
    */
  final def fromTask[A](ref: Ref[Task, A]): IORef[A] =
    new IORef[A] {
      override def get: UIO[A]                                           = ref.get.hideErrors
      override def set(a: A): UIO[Unit]                                  = ref.set(a).hideErrors
      override def access: UIO[(A, A => UIO[Boolean])]                   =
        ref.access.hideErrors.map { case (a, f) =>
          (a, a1 => f(a1).hideErrors)
        }
      override def tryUpdate(f: A => A): UIO[Boolean]                    = ref.tryUpdate(f).hideErrors
      override def tryModify[B](f: A => (A, B)): UIO[Option[B]]          = ref.tryModify(f).hideErrors
      override def update(f: A => A): UIO[Unit]                          = ref.update(f).hideErrors
      override def modify[B](f: A => (A, B)): UIO[B]                     = ref.modify(f).hideErrors
      override def tryModifyState[B](state: State[A, B]): UIO[Option[B]] = ref.tryModifyState(state).hideErrors
      override def modifyState[B](state: State[A, B]): UIO[B]            = ref.modifyState(state).hideErrors
    }
}
// $COVERAGE-ON$
