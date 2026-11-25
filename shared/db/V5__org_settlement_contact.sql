-- Top-level partner settlement contact, distinct from org_supply.settle_contact.
ALTER TABLE org ADD COLUMN settlement_contact varchar(20);
