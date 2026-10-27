package ai.msrv.gaterook.core.policy.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;

/** Table {@code policy}, keyed by env id ({@code dev}, {@code stage}, {@code prod}). */
public interface PolicyRepository extends JpaRepository<PolicyEntity, String> {

  /**
   * The number of rows, in SQL rather than {@link #count()}'s HQL, because every start asks it before seeding
   * (ARCHITECTURE.md §2.4, warm start): Hibernate parses HQL with ANTLR, and the first parse of a function call
   * ({@code count(*)}) was the costliest query of the start — 10 ms and 14 MB of the native one. A native query
   * isn't parsed.
   */
  @NativeQuery("select count(*) from policy")
  long rows();
}
