package cachemodeler_multyprocessor;

/**
 *
 * @author Taras
 */
//структура для запроса к памяти
//адрес и тип запроса
public class Query 
{
    public long address;
    public boolean isRead;
    public int number;
    public QueryType queryType;
    
    public Query(long address, boolean isRead, int number)
    {
        this.address = address;
        this.isRead = isRead;
        this.number = number;
        this.queryType = QueryType.UNDEFINED;
    }
}
