package LLD.LldQuestions.AtmTransfer.repository;

import LLD.LldQuestions.AtmTransfer.models.Account;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AccountRepository {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();

    public void save(Account account) {
        accounts.put(account.getId(), account);
    }

    public Account get(String id) {
        return accounts.get(id);
    }
}
