-- Design: "an unalterable audit trail for federal compliance audits"
CREATE TRIGGER ${orig}.TR_ACHOrigFedAction_NoUpdate BEFORE UPDATE ON ${orig}.ACHOrigFedAction
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ACHOrigFedAction is an audit trail: rows cannot be changed';

CREATE TRIGGER ${orig}.TR_ACHOrigFedAction_NoDelete BEFORE DELETE ON ${orig}.ACHOrigFedAction
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'ACHOrigFedAction is an audit trail: rows cannot be deleted';
