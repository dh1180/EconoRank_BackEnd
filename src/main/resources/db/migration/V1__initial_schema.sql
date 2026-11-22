CREATE TABLE country (code VARCHAR(3) PRIMARY KEY, name VARCHAR(120) NOT NULL, region VARCHAR(120) NOT NULL);
CREATE TABLE indicator (code VARCHAR(32) PRIMARY KEY, name VARCHAR(120) NOT NULL, unit VARCHAR(60) NOT NULL, source_url VARCHAR(300) NOT NULL);
CREATE TABLE dataset_release (id VARCHAR(50) PRIMARY KEY, retrieved_at VARCHAR(40) NOT NULL, source VARCHAR(80) NOT NULL);
CREATE TABLE observation (
  country_code VARCHAR(3) REFERENCES country(code), indicator_code VARCHAR(32) REFERENCES indicator(code),
  reference_year INTEGER NOT NULL, amount NUMERIC(30,4) NOT NULL CHECK (amount >= 0),
  release_id VARCHAR(50) NOT NULL REFERENCES dataset_release(id),
  PRIMARY KEY(country_code, indicator_code, reference_year)
);
CREATE INDEX observation_ranking ON observation(indicator_code, reference_year, amount DESC);
CREATE TABLE member (
  id VARCHAR(36) PRIMARY KEY, email VARCHAR(254) UNIQUE NOT NULL, display_name VARCHAR(40) NOT NULL,
  password_hash VARCHAR(100) NOT NULL, role VARCHAR(12) NOT NULL DEFAULT 'USER', created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE discussion_space (id VARCHAR(40) PRIMARY KEY, kind VARCHAR(16) NOT NULL, country_code VARCHAR(3) REFERENCES country(code));
INSERT INTO discussion_space(id, kind) VALUES ('global', 'GLOBAL');
CREATE TABLE post (
  id VARCHAR(36) PRIMARY KEY, space_id VARCHAR(40) NOT NULL REFERENCES discussion_space(id),
  author_id VARCHAR(36) NOT NULL REFERENCES member(id), title VARCHAR(160) NOT NULL, body VARCHAR(10000) NOT NULL,
  language VARCHAR(2) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'VISIBLE', created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX post_feed ON post(space_id, status, created_at DESC);
CREATE TABLE comment (
  id VARCHAR(36) PRIMARY KEY, post_id VARCHAR(36) NOT NULL REFERENCES post(id), author_id VARCHAR(36) NOT NULL REFERENCES member(id),
  body VARCHAR(3000) NOT NULL, status VARCHAR(12) NOT NULL DEFAULT 'VISIBLE', created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX comment_thread ON comment(post_id, status, created_at);
CREATE TABLE report (
  id VARCHAR(36) PRIMARY KEY, reporter_id VARCHAR(36) NOT NULL REFERENCES member(id),
  target_type VARCHAR(10) NOT NULL, target_id VARCHAR(36) NOT NULL, reason VARCHAR(500) NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'OPEN', created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(reporter_id, target_type, target_id)
);
CREATE TABLE member_block (blocker_id VARCHAR(36) REFERENCES member(id), blocked_id VARCHAR(36) REFERENCES member(id), PRIMARY KEY(blocker_id, blocked_id));
CREATE TABLE moderation_event (id VARCHAR(36) PRIMARY KEY, moderator_id VARCHAR(36) REFERENCES member(id), target_type VARCHAR(10) NOT NULL, target_id VARCHAR(36) NOT NULL, action VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE community_event (id VARCHAR(36) PRIMARY KEY, space_id VARCHAR(40) REFERENCES discussion_space(id), event_type VARCHAR(30) NOT NULL, aggregate_id VARCHAR(36) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, published_at TIMESTAMP WITH TIME ZONE);
