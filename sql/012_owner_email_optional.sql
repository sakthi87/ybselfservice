-- A signed-in user id may have no email address. The owner is still recorded
-- by platform_user_id, and the contact email stays empty until one is supplied.
ALTER TABLE catalog.data_owner ALTER COLUMN primary_contact_email DROP NOT NULL;
