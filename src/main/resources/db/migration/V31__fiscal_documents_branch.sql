-- A legal receipt or invoice belongs to the branch of its bill, so a branch manager's list and Z report hold only
-- that branch's documents (managers of all branches still see every one). Existing documents take their bill's branch.
ALTER TABLE fiscal_documents ADD COLUMN branch_id BIGINT REFERENCES branches (id);
UPDATE fiscal_documents SET branch_id = (SELECT b.branch_id FROM bills b WHERE b.id = fiscal_documents.bill_id);
UPDATE fiscal_documents SET branch_id = (SELECT MIN(br.id) FROM branches br WHERE br.business_id = fiscal_documents.business_id)
    WHERE branch_id IS NULL;
ALTER TABLE fiscal_documents ALTER COLUMN branch_id SET NOT NULL;
CREATE INDEX ix_fiscal_documents_branch ON fiscal_documents (branch_id);
