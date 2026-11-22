ALTER TABLE dataset_release ADD COLUMN published_at VARCHAR(40) NOT NULL DEFAULT '';
ALTER TABLE dataset_release ADD COLUMN edition VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE dataset_release ADD COLUMN source_url VARCHAR(500) NOT NULL DEFAULT '';
CREATE TABLE economic_observation (
 country_code VARCHAR(3) REFERENCES country(code), indicator_code VARCHAR(32) REFERENCES indicator(code),
 reference_year INTEGER NOT NULL, amount NUMERIC(30,4) NOT NULL CHECK(amount >= 0),
 release_id VARCHAR(50) NOT NULL REFERENCES dataset_release(id), provider VARCHAR(16) NOT NULL DEFAULT 'worldbank',
 observation_status VARCHAR(20) NOT NULL DEFAULT 'ACTUAL',
 PRIMARY KEY(country_code,indicator_code,reference_year,provider)
);
INSERT INTO economic_observation(country_code,indicator_code,reference_year,amount,release_id)
 SELECT country_code,indicator_code,reference_year,amount,release_id FROM observation;
DROP TABLE observation;
ALTER TABLE economic_observation RENAME TO observation;
CREATE INDEX observation_ranking ON observation(provider,indicator_code,reference_year,amount DESC);
CREATE TABLE data_sync_state (
 provider VARCHAR(16) PRIMARY KEY, checked_at VARCHAR(40) NOT NULL, succeeded_at VARCHAR(40),
 status VARCHAR(16) NOT NULL, message VARCHAR(500) NOT NULL
);
