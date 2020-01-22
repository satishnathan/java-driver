/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.driver.api.core.paging;

import com.datastax.oss.driver.api.core.AsyncPagingIterable;
import com.datastax.oss.driver.api.core.PagingIterable;
import com.datastax.oss.driver.shaded.guava.common.collect.ImmutableList;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.jcip.annotations.ThreadSafe;

/**
 * A utility to emulate offset queries on the client side (<b>this comes with important performance
 * trade-offs, make sure you read and understand the whole javadocs before using this class</b>).
 *
 * <p>Web UIs and REST services often provide paginated results with random access, for example:
 * given a page size of 20 elements, fetch page 5.
 *
 * <p>Cassandra does not support this natively (see <a
 * href="https://issues.apache.org/jira/browse/CASSANDRA-6511">CASSANDRA-6511</a>), because such
 * queries are inherently linear: the database would have to restart from the beginning every time,
 * and skip unwanted rows until it reaches the desired offset.
 *
 * <p>However, from a pragmatic point of view, offset queries have their place: random pagination is
 * a real need for many applications, and linear performance can be acceptable if the cardinality
 * stays reasonably low.
 *
 * <h3>Performance considerations</h3>
 *
 * In concrete terms, this class <em>does</em> start from the beginning and skip rows until it
 * reaches the desired page, every time. You need to re-execute the query in order to start with a
 * fresh result set for each invocation:
 *
 * <pre>
 * String query = "SELECT ...";
 * OffsetPager pager = new OffsetPager();
 * int pageSize = 20;
 *
 * ResultSet rs = session.execute(query);
 * // Iterate through rows 1-20 and discard them, then return rows 21-40
 * OffsetPager.Page&lt;Row&gt; page2 = pager.getPage(rs, 2, pageSize);
 *
 * rs = session.execute(query);
 * // Iterate through rows 1-80 and discard them, then return rows 81-100
 * OffsetPager.Page&lt;Row&gt; page5 = pager.getPage(rs, 5, pageSize);
 * </pre>
 *
 * This is fine for the values typically encountered in real-world applications: for example, if the
 * page size is 25 and users never go past page 10, the worst case is only 250 rows, which is a very
 * small result set.
 *
 * <p>On the other hand, if you want to display 10,000 elements per page and allow the user to query
 * page 7,384, this class is not going to work. You should actively defend against such aberrant
 * values, see the next paragraph.
 *
 * <h3>Establishing application-level guardrails</h3>
 *
 * If you use this class, we strongly recommend that you implement hard limits in your application
 * code. If the page number is exposed to the user (for example if it is passed as a URL parameter),
 * make sure it is properly validated and enforce a maximum, so that an attacker can't inject a
 * large value that could potentially fetch millions of rows.
 *
 * <h3>Relation with protocol-level paging</h3>
 *
 * There is also something called "paging" at the protocol level (controlled by the config option
 * {@code basic.request.page-size}). It simply means that the driver might receive the results in
 * multiple "chunks"; this happens under the hood and is completely transparent for users of this
 * class. You don't need to set the protocol page size to the same value as the logical page size.
 */
@ThreadSafe
public class OffsetPager {

  /** A page returned as the result of an offset query. */
  public interface Page<ElementT> {

    /** The elements in the page. */
    @NonNull
    List<ElementT> getElements();

    /**
     * The page number (1 for the first page, 2 for the second page, etc).
     *
     * <p>Note that it may be different than the requested page number: if the result set is too
     * short, {@link OffsetPager#getPage(PagingIterable, int, int)} returns the last page instead,
     * and this method will return the number of that last page.
     */
    int getPageNumber();

    /** Whether this is the last page in the result set. */
    boolean isLast();
  }

  /**
   * Skips the beginning of a set of results to return the given page, assuming the given page size.
   *
   * @param iterable the iterable to extract the results from.
   * @param targetPageNumber the page to return (1 for the first page, 2 for the second page, etc).
   *     Must be greater than or equal to 1.
   * @param pageSize the number of elements per page. Must be greater than or equal to 1.
   * @return the requested page, or the last page if the requested page was past the end of the
   *     iterable.
   * @throws IllegalArgumentException if the conditions on the arguments are not respected.
   */
  @NonNull
  public <ElementT> Page<ElementT> getPage(
      @NonNull PagingIterable<ElementT> iterable, final int targetPageNumber, final int pageSize) {

    throwIfIllegalArguments(iterable, targetPageNumber, pageSize);

    // Holds the contents of the target page. We also need to record the current page as we go,
    // because our iterable is forward-only and we can't predict when we'll hit the end.
    List<ElementT> currentPageElements = new ArrayList<>();

    int currentPageNumber = 1;
    int currentPageSize = 0;
    for (ElementT element : iterable) {
      currentPageSize += 1;

      if (currentPageSize > pageSize) {
        currentPageNumber += 1;
        currentPageSize = 1;
        currentPageElements.clear();
      }

      currentPageElements.add(element);

      if (currentPageNumber == targetPageNumber && currentPageSize == pageSize) {
        // The target page has the full size and we've seen all of its elements
        break;
      }
    }

    // Either we have the full target page, or we've reached the end of the result set.
    boolean isLast = iterable.one() == null;
    return new DefaultPage<>(currentPageElements, currentPageNumber, isLast);
  }

  /**
   * Skips the beginning of a set of results to return the given page, assuming the given page size.
   *
   * @param iterable the iterable to extract the results from.
   * @param targetPageNumber the page to return (1 for the first page, 2 for the second page, etc).
   *     Must be greater than or equal to 1.
   * @param pageSize the number of elements per page. Must be greater than or equal to 1.
   * @return a stage that will complete with the requested page, or the last page if the requested
   *     page was past the end of the iterable.
   * @throws IllegalArgumentException if the conditions on the arguments are not respected.
   */
  @NonNull
  public <ElementT, IterableT extends AsyncPagingIterable<ElementT, IterableT>>
      CompletionStage<Page<ElementT>> getPage(
          @NonNull IterableT iterable, final int targetPageNumber, final int pageSize) {

    // Throw IllegalArgumentException directly instead of failing the stage, since it signals
    // blatant programming errors
    throwIfIllegalArguments(iterable, targetPageNumber, pageSize);

    CompletableFuture<Page<ElementT>> pageFuture = new CompletableFuture<>();
    getPage(iterable, targetPageNumber, pageSize, 1, 0, new ArrayList<>(), pageFuture);

    return pageFuture;
  }

  private void throwIfIllegalArguments(
      @NonNull Object iterable, int targetPageNumber, int pageSize) {
    Objects.requireNonNull(iterable);
    if (targetPageNumber < 1) {
      throw new IllegalArgumentException(
          "Invalid targetPageNumber, expected >=1, got " + targetPageNumber);
    }
    if (pageSize < 1) {
      throw new IllegalArgumentException("Invalid pageSize, expected >=1, got " + pageSize);
    }
  }

  /**
   * Main method for the async iteration.
   *
   * <p>See the synchronous version in {@link #getPage(PagingIterable, int, int)} for more
   * explanations: this is identical, except that it is async and we need to handle protocol page
   * transitions manually.
   */
  private <IterableT extends AsyncPagingIterable<ElementT, IterableT>, ElementT> void getPage(
      @NonNull IterableT iterable,
      final int targetPageNumber,
      final int pageSize,
      int currentPageNumber,
      int currentPageSize,
      @NonNull List<ElementT> currentPageElements,
      @NonNull CompletableFuture<Page<ElementT>> pageFuture) {

    // Note: iterable.currentPage()/fetchNextPage() refer to protocol-level pages, do not confuse
    // with logical pages handled by this class
    Iterator<ElementT> currentFrame = iterable.currentPage().iterator();
    while (currentFrame.hasNext()) {
      ElementT element = currentFrame.next();

      currentPageSize += 1;

      if (currentPageSize > pageSize) {
        currentPageNumber += 1;
        currentPageSize = 1;
        currentPageElements.clear();
      }

      currentPageElements.add(element);

      if (currentPageNumber == targetPageNumber && currentPageSize == pageSize) {
        // Full-size target page. In this method it's simpler to finish directly here.
        if (currentFrame.hasNext()) {
          pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, false));
        } else if (!iterable.hasMorePages()) {
          pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, true));
        } else {
          // It's possible for the server to return an empty last frame, so we need to fetch it to
          // know for sure whether there are more elements
          int finalCurrentPageNumber = currentPageNumber;
          iterable
              .fetchNextPage()
              .whenComplete(
                  (nextIterable, throwable) -> {
                    if (throwable != null) {
                      pageFuture.completeExceptionally(throwable);
                    } else {
                      boolean isLastPage = !nextIterable.currentPage().iterator().hasNext();
                      pageFuture.complete(
                          new DefaultPage<>(
                              currentPageElements, finalCurrentPageNumber, isLastPage));
                    }
                  });
        }
        return;
      }
    }

    if (iterable.hasMorePages()) {
      int finalCurrentPageNumber = currentPageNumber;
      int finalCurrentPageSize = currentPageSize;
      iterable
          .fetchNextPage()
          .whenComplete(
              (nextIterable, throwable) -> {
                if (throwable != null) {
                  pageFuture.completeExceptionally(throwable);
                } else {
                  getPage(
                      nextIterable,
                      targetPageNumber,
                      pageSize,
                      finalCurrentPageNumber,
                      finalCurrentPageSize,
                      currentPageElements,
                      pageFuture);
                }
              });
    } else {
      // Reached the end of the result set, finish with what we have so far
      pageFuture.complete(new DefaultPage<>(currentPageElements, currentPageNumber, true));
    }
  }

  private static class DefaultPage<ElementT> implements Page<ElementT> {
    private final List<ElementT> elements;
    private final int pageNumber;
    private final boolean isLast;

    DefaultPage(@NonNull List<ElementT> elements, int pageNumber, boolean isLast) {
      this.elements = ImmutableList.copyOf(elements);
      this.pageNumber = pageNumber;
      this.isLast = isLast;
    }

    @NonNull
    @Override
    public List<ElementT> getElements() {
      return elements;
    }

    @Override
    public int getPageNumber() {
      return pageNumber;
    }

    @Override
    public boolean isLast() {
      return isLast;
    }
  }
}