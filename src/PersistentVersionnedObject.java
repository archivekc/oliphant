import javax.persistence.Id;
import javax.persistence.Entity;

@Entity
public class PersistentObject {

	private long id;
	private String champString;
	private Long champLong;
	
	@Id
	public long getId() {
		return id;
	}
	public void setId(long id) {
		this.id = id;
	}
	public String getChampString() {
		return champString;
	}	
	public void setChampString(String a) {
		this.champString = a;
	}
	public Long getChampLong() {
		return champLong;
	}
	public void setChampLong(Long a) {
		this.champLong = a;
	}
}
