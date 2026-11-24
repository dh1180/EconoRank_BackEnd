CREATE TABLE post_like (
  post_id VARCHAR(36) NOT NULL REFERENCES post(id) ON DELETE CASCADE,
  member_id VARCHAR(36) NOT NULL REFERENCES member(id) ON DELETE CASCADE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (post_id, member_id)
);
CREATE INDEX idx_post_like_member ON post_like(member_id, created_at DESC);
