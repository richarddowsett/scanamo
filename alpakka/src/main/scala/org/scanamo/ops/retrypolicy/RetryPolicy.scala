package org.scanamo.ops.retrypolicy

import java.util.concurrent.ScheduledExecutorService

import org.scanamo.ops.retrypolicy.RetryPolicy._

import scala.concurrent.duration.FiniteDuration

sealed abstract class RetryPolicy extends Product with Serializable { self =>
  final def maybeScheduler: Option[ScheduledExecutorService] = self match {
    case policy @ Constant(_)        => Some(policy.scheduler)
    case policy @ Linear(_, _)       => Some(policy.scheduler)
    case policy @ Exponential(_, _)  => Some(policy.scheduler)
    case And(thisPolicy, thatPolicy) => thisPolicy.maybeScheduler orElse thatPolicy.maybeScheduler
    case Or(thisPolicy, thatPolicy)  => thisPolicy.maybeScheduler orElse thatPolicy.maybeScheduler
    case _                           => None
  }

  final def done: Boolean = self match {
    case Max(1)                      => true
    case And(thisPolicy, thatPolicy) => thisPolicy.done && thatPolicy.done
    case Or(thisPolicy, thatPolicy)  => thisPolicy.done || thatPolicy.done
    case Never                       => true
    case _                           => false
  }

  final def delay: Long = self match {
    case Constant(retryDelay)       => retryDelay.toMillis
    case Linear(retryDelay, factor) => retryDelay.toMillis * factor.toLong
    case Exponential(retryDelay, factor) =>
      val delayMillis = retryDelay.toMillis
      delayMillis * Math.pow(delayMillis.toDouble, factor).toLong
    case And(thisPolicy, thatPolicy) => Math.max(thisPolicy.delay, thatPolicy.delay)
    case Or(thisPolicy, thatPolicy)  => Math.min(thisPolicy.delay, thatPolicy.delay)
    case _                           => 0
  }

  final def update: RetryPolicy = self match {
    case policy: Constant[_]         => policy
    case policy: Linear[_]           => policy
    case policy: Exponential[_]      => policy
    case Max(retries)                => Max(retries - 1)
    case And(thisPolicy, thatPolicy) => And(thisPolicy.update, thatPolicy.update)
    case Or(thisPolicy, thatPolicy)  => Or(thisPolicy.update, thatPolicy.update)
    case retryPolicy                 => retryPolicy
  }

  final val retries: Int = self match {
    case Max(retries) => retries
    case And(x, y)    => Math.min(x.retries, y.retries)
    case Or(x, y)     => Math.min(x.retries, y.retries)
    case _            => 0
  }

  final def &&(that: RetryPolicy): RetryPolicy = And(self, that)
  final def ||(that: RetryPolicy): RetryPolicy = Or(self, that)
}

object RetryPolicy {
  final case class Constant[S <: ScheduledExecutorService](retryDelay: FiniteDuration)(
    implicit val scheduler: S
  ) extends RetryPolicy

  final case class Linear[S <: ScheduledExecutorService](
    retryDelay: FiniteDuration,
    factor: Double
  )(implicit val scheduler: S)
      extends RetryPolicy

  final case class Exponential[S <: ScheduledExecutorService](
    retryDelay: FiniteDuration,
    factor: Double
  )(implicit val scheduler: S)
      extends RetryPolicy

  final case class Max(numberOfRetries: Int) extends RetryPolicy
  final case class And(thisPolicy: RetryPolicy, thatPolicy: RetryPolicy) extends RetryPolicy
  final case class Or(thisPolicy: RetryPolicy, thatPolicy: RetryPolicy) extends RetryPolicy
  final case object Always extends RetryPolicy
  final case object Never extends RetryPolicy
}
