package com.umutvarol.restaurant.business;

import com.umutvarol.restaurant.common.error.AccessRefusedException;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens, renames and closes a chain's branches. Only managers who work in all branches change them. A closed branch
 * keeps its history; the last open branch cannot be closed.
 */
@Service
@Transactional
public class BranchService {

    private static final Logger audit = LoggerFactory.getLogger("business.audit");

    private final BranchRepository branches;
    private final Clock clock;

    BranchService(BranchRepository branches, Clock clock) {
        this.branches = branches;
        this.clock = clock;
    }

    /** Every branch for those who work in all of them; their own for everyone else. */
    @Transactional(readOnly = true)
    public List<Branch> visibleTo(@Nullable Long home) {
        return branches.findAllByOrderByIdAsc().stream()
                .filter(b -> home == null || b.getId().equals(home))
                .toList();
    }

    public Branch open(String code, String name, @Nullable Long home, String actor) {
        requireChainManager(home);
        if (branches.findByCode(code).isPresent()) {
            throw new InvalidStateException("BRANCH_CODE_TAKEN", "There is already a branch " + code, Map.of("code", code));
        }
        Branch branch = branches.save(new Branch(code, name.strip(), clock.instant()));
        audit.info("branch.opened id={} code={} by={}", branch.getId(), branch.getCode(), actor);
        return branch;
    }

    public Branch update(Long id, String name, boolean active, @Nullable Long home, String actor) {
        requireChainManager(home);
        Branch branch = branches.findById(id).orElseThrow(() -> new ResourceNotFoundException("branch", id));
        if (branch.isActive() && !active && branches.findAllByOrderByIdAsc().stream().filter(Branch::isActive).count() <= 1) {
            throw new BusinessRuleException("LAST_BRANCH", "A business needs at least one open branch");
        }
        branch.rename(name.strip());
        branch.setActive(active);
        audit.info("branch.changed id={} name=\"{}\" active={} by={}", id, branch.getName(), branch.isActive(), actor);
        return branch;
    }

    private static void requireChainManager(@Nullable Long home) {
        if (home != null) {
            throw new AccessRefusedException("BRANCH_MANAGER_ONLY", "Only managers of all branches change branches");
        }
    }
}
