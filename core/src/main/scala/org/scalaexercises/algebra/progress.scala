/*
 * scala-exercises - core
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package org.scalaexercises.algebra.progress

import org.scalaexercises.algebra.exercises.ExerciseOps
import org.scalaexercises.types.user._
import org.scalaexercises.types.exercises._
import org.scalaexercises.types.progress._

import cats.{ Monad, Applicative, Unapply }
import cats.free._
import cats.implicits._

import freestyle._
import freestyle.implicits._

object imp {
  implicit def freeSap[F[_]]: Applicative[FreeS[F, ?]] = new Applicative[FreeS[F, ?]] {
    def pure[A](x: A): FreeS[F, A] = Free.liftF[FreeApplicative[F, ?], A](
      FreeApplicative.pure(x)
    )

    def ap[A, B](ff: FreeS[F, A ⇒ B])(fa: FreeS[F, A]): FreeS[F, B] =
      ff.ap(fa)
  }
}
import imp._

/** Exposes User Progress operations as a Free monadic algebra that may be combined with other Algebras via
  * Coproduct
  */
@free trait UserProgressOps[F[_]] {
  def saveUserProgress(userProgress: SaveUserProgress.Request): FreeS[F, UserProgress]

  def getExerciseEvaluations(user: User, library: String, section: String): FreeS[F, List[UserProgress]]

  def getLastSeenSection(user: User, library: String): FreeS[F, Option[String]]

  def getSolvedExerciseCount(user: User, library: String, section: String): FreeS[F, Int] =
    getExerciseEvaluations(user, library, section).map(tried ⇒ tried.count(_.succeeded))

  def fetchMaybeUserProgress(user: Option[User])(implicit EO: ExerciseOps[F]): FreeS[F, OverallUserProgress] = {
    user.fold(anonymousUserProgress)(fetchUserProgress)
  }

  private[this] def anonymousUserProgress(implicit EO: ExerciseOps[F]): FreeS[F, OverallUserProgress] = for {
    libraries ← EO.getLibraries
    libs = libraries.map(l ⇒ {
      OverallUserProgressItem(
        libraryName = l.name,
        completedSections = 0,
        totalSections = l.sections.size
      )
    })
  } yield OverallUserProgress(libraries = libs)

  def getCompletedSectionCount(user: User, library: Library): FreeS[F, Int] =
    library.sections.traverse[FreeS[F, ?], Boolean](isSectionCompleted(user, library.name, _))(freeSap[F]).map(
      _.count(identity)
    )

  private[this] def isSectionCompleted(user: User, libraryName: String, section: Section): FreeS[F, Boolean] =
    getSolvedExerciseCount(user, libraryName, section.name).map(solvedExercises ⇒
      solvedExercises == section.exercises.size)

  def fetchUserProgress(user: User)(
    implicit
    EO: ExerciseOps[F]
  ): FreeS[F, OverallUserProgress] = {
    def getLibraryProgress(library: Library): FreeS[F, OverallUserProgressItem] =
      getCompletedSectionCount(user, library).map { completedSections ⇒
        OverallUserProgressItem(
          libraryName = library.name,
          completedSections = completedSections,
          totalSections = library.sections.size
        )
      }

    for {
      allLibraries ← EO.getLibraries
      libraryProgress ← allLibraries.traverse[FreeS[F, ?], OverallUserProgressItem](getLibraryProgress)(freeSap[F])
    } yield OverallUserProgress(libraries = libraryProgress)
  }

  def fetchMaybeUserProgressByLibrary(user: Option[User], libraryName: String)(implicit EO: ExerciseOps[F]): FreeS[F, LibraryProgress] = {
    user.fold(anonymousUserProgressByLibrary(libraryName))(fetchUserProgressByLibrary(_, libraryName))
  }

  private[this] def anonymousUserProgressByLibrary(libraryName: String)(implicit EO: ExerciseOps[F]): FreeS[F, LibraryProgress] = {
    for {
      lib ← EO.getLibrary(libraryName)
      sections = lib.foldMap(_.sections.map(s ⇒
        SectionProgress(
          sectionName = s.name,
          succeeded = false
        )))
    } yield LibraryProgress(
      libraryName = libraryName,
      sections = sections
    )
  }

  def fetchUserProgressByLibrary(user: User, libraryName: String)(
    implicit
    EO: ExerciseOps[F]
  ): FreeS[F, LibraryProgress] = {
    def getSectionProgress(section: Section): FreeS[F, SectionProgress] =
      isSectionCompleted(user, libraryName, section).map { completed ⇒
        SectionProgress(
          sectionName = section.name,
          succeeded = completed
        )
      }

    for {
      maybeLib ← EO.getLibrary(libraryName)
      libSections = maybeLib.foldMap(_.sections)
      sectionProgress ← libSections.traverse[FreeS[F, ?], SectionProgress](getSectionProgress)(freeSap[F])
    } yield LibraryProgress(
      libraryName = libraryName,
      sections = sectionProgress
    )
  }

  def fetchUserProgressByLibrarySection(
    user:        User,
    libraryName: String,
    sectionName: String
  )(implicit EO: ExerciseOps[F]): FreeS[F, SectionExercises] = {
    for {
      maybeSection ← EO.getSection(libraryName, sectionName)
      evaluations ← getExerciseEvaluations(user, libraryName, sectionName)
      sectionExercises = maybeSection.foldMap(_.exercises)
      exercises = sectionExercises.map(ex ⇒ {
        val maybeEvaluation = evaluations.find(_.method == ex.method)
        ExerciseProgress(
          methodName = ex.method,
          args = maybeEvaluation.foldMap(_.args),
          succeeded = maybeEvaluation.fold(false)(_.succeeded)
        )
      })
      totalExercises = sectionExercises.size
    } yield SectionExercises(
      libraryName = libraryName,
      sectionName = sectionName,
      exercises = exercises,
      totalExercises = totalExercises
    )
  }
}

