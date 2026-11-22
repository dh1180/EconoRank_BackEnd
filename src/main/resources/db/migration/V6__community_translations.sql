CREATE TABLE content_translation (
  target_type VARCHAR(16) NOT NULL,
  target_id VARCHAR(36) NOT NULL,
  target_lang VARCHAR(8) NOT NULL,
  translated_title VARCHAR(240),
  translated_body TEXT NOT NULL,
  provider VARCHAR(40) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (target_type, target_id, target_lang)
);
