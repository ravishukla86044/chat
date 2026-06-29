-- Add a required, unique email to users.
-- Additive migration: add the column nullable, backfill the seeded users, then
-- enforce NOT NULL + UNIQUE so no two users can share an email.

ALTER TABLE users ADD COLUMN email VARCHAR(255) NULL AFTER username;

UPDATE users SET email = 'alice@example.com' WHERE id = 1;
UPDATE users SET email = 'bob@example.com'   WHERE id = 2;
UPDATE users SET email = 'carol@example.com' WHERE id = 3;

ALTER TABLE users MODIFY COLUMN email VARCHAR(255) NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
