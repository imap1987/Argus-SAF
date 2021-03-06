/*
 * Copyright (c) 2018. Fengguo Wei and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License v2.0
 * which accompanies this distribution, and is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Detailed contributors are listed in the CONTRIBUTOR.md
 */

package org.argus.jawa.core.classpath

import java.net.URL

import org.argus.jawa.core.io.AbstractFile

import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer

/**
 * A classpath unifying multiple class- and sourcepath entries.
 * Flat classpath can obtain entries for classes and sources independently
 * so it tries to do operations quite optimally - iterating only these collections
 * which are needed in the given moment and only as far as it's necessary.
 * @param aggregates classpath instances containing entries which this class processes
 */
case class AggregateFlatClasspath(aggregates: Seq[FlatClasspath]) extends FlatClasspath {

  override def findClassFile(className: String): Option[AbstractFile] = {
    @tailrec
    def find(aggregates: Seq[FlatClasspath]): Option[AbstractFile] =
      if (aggregates.nonEmpty) {
        val classFile = aggregates.head.findClassFile(className)
        if (classFile.isDefined) classFile
        else find(aggregates.tail)
      } else None

    find(aggregates)
  }

  override def findClass(className: String): Option[ClassRepresentation] = {
    val (pkg, simpleClassName) = PackageNameUtils.separatePkgAndClassNames(className)

    @tailrec
    def findEntry[T <: ClassRepClasspathEntry](aggregates: Seq[FlatClasspath], getEntries: FlatClasspath => Seq[T]): Option[T] =
      if (aggregates.nonEmpty) {
        val entry = getEntries(aggregates.head)
          .find(_.name == simpleClassName)
        if (entry.isDefined) entry
        else findEntry(aggregates.tail, getEntries)
      } else None

    val classEntry = findEntry(aggregates, classesGetter(pkg))
    val sourceEntry = findEntry(aggregates, sourcesGetter(pkg))

    mergeClassesAndSources(classEntry.toList, sourceEntry.toList).headOption
  }

  override def asURLs: Seq[URL] = aggregates.flatMap(_.asURLs)

  override def asClasspathStrings: Seq[String] = aggregates.map(_.asClasspathString).distinct

  override def asSourcePathString: String = Classpath.join(aggregates map (_.asSourcePathString): _*)

  override private[jawa] def packages(inPackage: String): Seq[PackageEntry] = {
    val aggregatedPackages = aggregates.flatMap(_.packages(inPackage)).distinct
    aggregatedPackages
  }

  override private[jawa] def classes(inPackage: String): Seq[ClassFileEntry] =
    getDistinctEntries(classesGetter(inPackage))

  override private[jawa] def sources(inPackage: String): Seq[SourceFileEntry] =
    getDistinctEntries(sourcesGetter(inPackage))

  override private[jawa] def list(inPackage: String): FlatClasspathEntries = {
    val (packages, classesAndSources) = aggregates.map(_.list(inPackage)).unzip
    val distinctPackages = packages.flatten.distinct
    val distinctClassesAndSources = mergeClassesAndSources(classesAndSources: _*)
    FlatClasspathEntries(distinctPackages, distinctClassesAndSources)
  }

  /**
   * Returns only one entry for each name. If there's both a source and a class entry, it
   * creates an entry containing both of them. If there would be more than one class or source
   * entries for the same class it always would use the first entry of each type found on a classpath.
   */
  private def mergeClassesAndSources(entries: Seq[ClassRepClasspathEntry]*): Seq[ClassRepClasspathEntry] = {
    // based on the implementation from MergedClasspath
    var count = 0
    val indices = collection.mutable.HashMap[String, Int]()
    val mergedEntries = new ArrayBuffer[ClassRepClasspathEntry](1024)

    for {
      partOfEntries <- entries
      entry <- partOfEntries
    } {
      val name = entry.name
      if (indices contains name) {
        val index = indices(name)
        val existing = mergedEntries(index)

        if (existing.binary.isEmpty && entry.binary.isDefined)
          mergedEntries(index) = ClassAndSourceFilesEntry(entry.binary.get, existing.source.get)
        if (existing.source.isEmpty && entry.source.isDefined)
          mergedEntries(index) = ClassAndSourceFilesEntry(existing.binary.get, entry.source.get)
      }
      else {
        indices(name) = count
        mergedEntries += entry
        count += 1
      }
    }
    mergedEntries.toIndexedSeq
  }

  private def getDistinctEntries[EntryType <: ClassRepClasspathEntry](getEntries: FlatClasspath => Seq[EntryType]): Seq[EntryType] = {
    val seenNames = collection.mutable.HashSet[String]()
    val entriesBuffer = new ArrayBuffer[EntryType](1024)
    for {
      cp <- aggregates
      entry <- getEntries(cp) if !seenNames.contains(entry.name)
    } {
      entriesBuffer += entry
      seenNames += entry.name
    }
    entriesBuffer.toIndexedSeq
  }

  private def classesGetter(pkg: String) = (cp: FlatClasspath) => cp.classes(pkg)
  private def sourcesGetter(pkg: String) = (cp: FlatClasspath) => cp.sources(pkg)
}
