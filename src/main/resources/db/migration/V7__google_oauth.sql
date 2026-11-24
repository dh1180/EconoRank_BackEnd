ALTER TABLE member ADD COLUMN google_sub VARCHAR(128);
CREATE UNIQUE INDEX idx_member_google_sub ON member(google_sub);
